import time
import math
import numpy as np
import pyaudiowpatch as pyaudio
from scipy.signal import resample_poly
from PyQt6.QtCore import QThread, pyqtSignal

def resample_audio(audio_data: np.ndarray, orig_sr: int, target_sr: int = 16000) -> np.ndarray:
    if orig_sr == target_sr:
        return audio_data
    g = math.gcd(orig_sr, target_sr)
    up = target_sr // g
    down = orig_sr // g
    return resample_poly(audio_data, up, down).astype(np.float32)

class AudioCaptureThread(QThread):
    audio_segment_ready = pyqtSignal(np.ndarray)
    audio_level_updated = pyqtSignal(float)
    status_changed = pyqtSignal(str)

    def __init__(self, config):
        super().__init__()
        self.config = config
        self.running = False

    def stop(self):
        self.running = False
        self.wait(1000)

    def run(self):
        self.running = True
        p = pyaudio.PyAudio()
        stream = None

        try:
            try:
                wasapi_info = p.get_host_api_info_by_type(pyaudio.paWASAPI)
            except Exception as e:
                self.status_changed.emit(f"خطأ: تعذر العثور على WASAPI ({e})")
                return

            default_speakers = p.get_device_info_by_index(wasapi_info["defaultOutputDevice"])
            loopback_device = None

            if default_speakers.get("isLoopbackDevice"):
                loopback_device = default_speakers
            else:
                for dev in p.get_loopback_device_info_generator():
                    if default_speakers["name"] in dev["name"]:
                        loopback_device = dev
                        break

            if loopback_device is None:
                for dev in p.get_loopback_device_info_generator():
                    loopback_device = dev
                    break

            if loopback_device is None:
                self.status_changed.emit("خطأ: لم يتم العثور على جهاز صوت Loopback في النظام")
                return

            sample_rate = int(loopback_device["defaultSampleRate"])
            channels = int(loopback_device["maxInputChannels"])
            
            # حزم صوتية سريعة بحجم 40 مللي ثانية فقط
            chunk_ms = 0.04
            chunk_samples = int(sample_rate * chunk_ms)

            stream = p.open(
                format=pyaudio.paInt16,
                channels=channels,
                rate=sample_rate,
                input=True,
                input_device_index=loopback_device["index"],
                frames_per_buffer=chunk_samples
            )

            is_speaking = False
            speech_chunks = []
            silence_duration = 0.0
            total_speech_time = 0.0

            vad_threshold = float(self.config.get("vad_threshold", 0.012))
            min_speech_duration = float(self.config.get("min_speech_duration", 0.25))
            silence_timeout = float(self.config.get("silence_duration", 0.25))
            max_speech_duration = float(self.config.get("max_speech_duration", 1.6))

            while self.running:
                try:
                    raw_bytes = stream.read(chunk_samples, exception_on_overflow=False)
                except Exception:
                    continue

                if not raw_bytes or not self.running:
                    break

                chunk_int16 = np.frombuffer(raw_bytes, dtype=np.int16)
                if len(chunk_int16) == 0:
                    continue

                chunk_float = chunk_int16.astype(np.float32) / 32768.0

                if channels > 1:
                    chunk_float = chunk_float.reshape(-1, channels).mean(axis=1)

                rms = float(np.sqrt(np.mean(chunk_float ** 2)))
                self.audio_level_updated.emit(min(rms * 12.0, 1.0))

                chunk_16k = resample_audio(chunk_float, sample_rate, 16000)

                # آلية VAD السريعة
                if rms >= vad_threshold:
                    if not is_speaking:
                        is_speaking = True
                        speech_chunks = []
                        total_speech_time = 0.0
                        silence_duration = 0.0

                    speech_chunks.append(chunk_16k)
                    total_speech_time += chunk_ms
                    silence_duration = 0.0

                    # قطع وإرسال فوري كل 1.6 ثانية لضمان عدم تأخر الترجمة عن الفيديو
                    if total_speech_time >= max_speech_duration:
                        if len(speech_chunks) > 0:
                            full_segment = np.concatenate(speech_chunks)
                            self.audio_segment_ready.emit(full_segment)
                        # الاحتفاظ بآخر 100ms للربط الصوتي الطبيعي
                        speech_chunks = speech_chunks[-3:] if len(speech_chunks) > 3 else []
                        total_speech_time = 0.1
                        silence_duration = 0.0

                elif is_speaking:
                    speech_chunks.append(chunk_16k)
                    silence_duration += chunk_ms
                    total_speech_time += chunk_ms

                    if silence_duration >= silence_timeout:
                        speech_len = total_speech_time - silence_duration
                        if speech_len >= min_speech_duration and len(speech_chunks) > 0:
                            full_segment = np.concatenate(speech_chunks)
                            self.audio_segment_ready.emit(full_segment)

                        is_speaking = False
                        speech_chunks = []
                        total_speech_time = 0.0
                        silence_duration = 0.0

        except Exception as e:
            self.status_changed.emit(f"خطأ في مسار الصوت: {e}")
        finally:
            if stream is not None:
                try:
                    stream.stop_stream()
                    stream.close()
                except Exception:
                    pass
            p.terminate()
