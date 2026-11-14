import os
import io
import time
import queue
import re
import requests
import numpy as np
from scipy.io import wavfile
from faster_whisper import WhisperModel
from PyQt6.QtCore import QThread, pyqtSignal

os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

# ذاكرة تخزين مؤقت للترجمات
TRANSLATION_CACHE = {}

# جلسة اتصال دائمة لسرعة الاستجابة
HTTP_SESSION = requests.Session()
HTTP_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

def contains_arabic(text: str) -> bool:
    """التحقق من أن النص يحتوي فعلياً على حروف عربية"""
    if not text:
        return False
    return any('\u0600' <= c <= '\u06FF' for c in text)

def translate_english_to_arabic(text: str) -> str:
    """
    ترجمة نص إنجليزي إلى عربي بدقة وسرعة فائقة (~40ms)
    مع التحقق الصارم من أن النتيجة عربية وليست إنجليزية
    """
    cleaned = text.strip()
    if not cleaned:
        return ""

    if cleaned in TRANSLATION_CACHE:
        return TRANSLATION_CACHE[cleaned]

    # 1. الاتصال بنقطة Google GTX السريعة
    try:
        url = "https://translate.googleapis.com/translate_a/single"
        params = {
            "client": "gtx",
            "sl": "en",
            "tl": "ar",
            "dt": "t",
            "q": cleaned
        }
        resp = HTTP_SESSION.get(url, params=params, headers=HTTP_HEADERS, timeout=4)
        if resp.status_code == 200:
            data = resp.json()
            translated = "".join([part[0] for part in data[0] if part and part[0]]).strip()
            if contains_arabic(translated):
                if len(TRANSLATION_CACHE) > 500:
                    TRANSLATION_CACHE.clear()
                TRANSLATION_CACHE[cleaned] = translated
                return translated
    except Exception:
        pass

    # 2. نقطة اتصال بديلة (Chrome Extension endpoint) الرسمية وفائقة الاعتمادية
    try:
        url2 = "https://clients5.google.com/translate_a/t"
        params2 = {
            "client": "dict-chrome-ex",
            "sl": "en",
            "tl": "ar",
            "q": cleaned
        }
        resp2 = HTTP_SESSION.get(url2, params=params2, headers=HTTP_HEADERS, timeout=4)
        if resp2.status_code == 200:
            data2 = resp2.json()
            if isinstance(data2, list) and len(data2) > 0:
                trans = data2[0] if isinstance(data2[0], str) else data2[0][0]
                trans = trans.strip()
                if contains_arabic(trans):
                    TRANSLATION_CACHE[cleaned] = trans
                    return trans
    except Exception:
        pass

    # 3. محرك بديل عبر Deep Translator
    try:
        from deep_translator import GoogleTranslator
        trans3 = GoogleTranslator(source='en', target='ar').translate(cleaned)
        if contains_arabic(trans3):
            TRANSLATION_CACHE[cleaned] = trans3
            return trans3
    except Exception:
        pass

    return ""

def transcribe_with_groq(audio_float32: np.ndarray, api_key: str) -> str:
    """تفريغ صوتي عبر Groq السحابي في ~150ms"""
    try:
        audio_int16 = (audio_float32 * 32767).astype(np.int16)
        buffer = io.BytesIO()
        wavfile.write(buffer, 16000, audio_int16)
        buffer.seek(0)

        url = "https://api.groq.com/openai/v1/audio/transcriptions"
        headers = {"Authorization": f"Bearer {api_key}"}
        files = {"file": ("audio.wav", buffer.read(), "audio/wav")}
        data = {
            "model": "whisper-large-v3-turbo",
            "language": "en",
            "temperature": "0.0",
            "response_format": "text"
        }
        r = requests.post(url, headers=headers, files=files, data=data, timeout=4)
        if r.status_code == 200:
            return r.text.strip()
    except Exception:
        pass
    return ""

class TranscriberThread(QThread):
    subtitle_ready = pyqtSignal(str, str)
    status_changed = pyqtSignal(str)

    def __init__(self, config):
        super().__init__()
        self.config = config
        self.running = False
        self.audio_queue = queue.Queue(maxsize=8)
        self.model = None

    def push_audio(self, audio_data: np.ndarray):
        try:
            if self.audio_queue.full():
                try:
                    self.audio_queue.get_nowait()
                except queue.Empty:
                    pass
            self.audio_queue.put_nowait(audio_data)
        except Exception:
            pass

    def stop(self):
        self.running = False
        self.audio_queue.put(None)
        self.wait(1500)

    def run(self):
        self.running = True
        groq_key = self.config.get("groq_api_key", "").strip()
        model_name = self.config.get("model_name", "tiny.en")
        cpu_threads = int(self.config.get("cpu_threads", os.cpu_count() or 4))

        if groq_key:
            self.status_changed.emit("يعمل بمحرك Groq Turbo السحابي ⚡")
        else:
            self.status_changed.emit(f"تحميل النموذج المحلي ({model_name})...")
            try:
                self.model = WhisperModel(
                    model_name,
                    device="cpu",
                    compute_type="int8",
                    cpu_threads=cpu_threads
                )
                self.status_changed.emit(f"النموذج جاهز ({model_name}) - استماع مباشر...")
            except Exception as e:
                self.status_changed.emit(f"خطأ في النموذج: {e}")
                return

        while self.running:
            try:
                audio_data = self.audio_queue.get(timeout=0.3)
            except queue.Empty:
                continue

            if audio_data is None or not self.running:
                break

            if len(audio_data) < 2400:
                continue

            t0 = time.time()
            en_text = ""

            if groq_key:
                en_text = transcribe_with_groq(audio_data, groq_key)
            elif self.model is not None:
                try:
                    segments, _ = self.model.transcribe(
                        audio_data,
                        language="en",
                        beam_size=1,
                        best_of=1,
                        temperature=0.0,
                        condition_on_previous_text=False,
                        without_timestamps=True,
                        vad_filter=False
                    )
                    en_text = " ".join([s.text for s in segments]).strip()
                except Exception:
                    continue

            if en_text and len(en_text) >= 2:
                low = en_text.lower().strip(" .!?,")
                if low in ["thank you", "thanks for watching", "subtitles by", "you", "bye"]:
                    continue

                # ترجمة أكيدة إلى العربية
                ar_text = translate_english_to_arabic(en_text)
                elapsed = time.time() - t0

                # إذا لم تتوفر ترجمة عربية صالحة، لا نعرض النص الإنجليزي في مكان العربي
                if ar_text:
                    self.status_changed.emit(f"⚡ تمت الترجمة في {elapsed:.2f} ثانية")
                    self.subtitle_ready.emit(en_text, ar_text)

        self.status_changed.emit("تم إيقاف المعالجة")
