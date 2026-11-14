import os
import json

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULT_CONFIG = {
    "engine_mode": "auto",         # "auto" (يفضل LiveCaptions إن وجد), "live_captions", "whisper_local", "groq_cloud"
    "groq_api_key": "",            # مفتاح Groq مجاني لسرعة 0.15 ثانية ودقة استثنائية (اختياري)
    "model_name": "tiny.en",       # "tiny.en" (فائق السرعة) أو "base.en"
    "cpu_threads": os.cpu_count() or 4,
    "vad_threshold": 0.012,        # حساسية التقاط الصوت
    "min_speech_duration": 0.25,   # حد أدنى سريع 250ms
    "silence_duration": 0.25,      # مهلة صمت سريعة 250ms لإنهاء الجملة فوراً
    "max_speech_duration": 1.6,    # تجزئة سريعة كل 1.6 ثانية لتجنب أي تأخير أثناء الحديث
    "display_mode": "bilingual",   # "ar_only" أو "bilingual"
    "font_size": 22,
    "text_color": "#FFEB3B",
    "bg_opacity": 190,
    "click_through": False,
    "window_width": 860,
    "window_height": 95,
    "window_x": -1,
    "window_y": -1
}

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                saved = json.load(f)
                config = DEFAULT_CONFIG.copy()
                config.update(saved)
                return config
        except Exception:
            return DEFAULT_CONFIG.copy()
    return DEFAULT_CONFIG.copy()

def save_config(config):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4, ensure_ascii=False)
    except Exception as e:
        print(f"Error saving config: {e}")
