import time
import re
import uiautomation as auto
from PyQt6.QtCore import QThread, pyqtSignal
from transcriber import translate_english_to_arabic, contains_arabic

def clean_caption_text(text: str) -> str:
    if not text:
        return ""
    ignored_phrases = [
        "ready to caption",
        "ready to caption.",
        "مرحباً بك",
        "هل تريد المتابعة"
    ]
    cleaned = text.strip()
    for phrase in ignored_phrases:
        if phrase.lower() in cleaned.lower():
            return ""
    return cleaned

class WindowsLiveCaptionsWatcher(QThread):
    subtitle_ready = pyqtSignal(str, str)   # يرسل (النص الإنجليزي, الترجمة العربية)
    status_changed = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.running = False
        self.last_text = ""

    def stop(self):
        self.running = False
        self.wait(1000)

    def run(self):
        self.running = True
        desktop = auto.GetRootControl()

        self.status_changed.emit("جاري البحث عن نافذة Windows Live Captions...")

        while self.running:
            captions_window = desktop.Control(searchDepth=1, ClassName="LiveCaptionsDesktopWindow")
            if not captions_window.Exists(0.5):
                time.sleep(1.0)
                continue

            self.status_changed.emit("متصل بـ Live Captions (تأخير صفر - Zero Delay) ⚡")

            scroll_viewer = captions_window.Control(searchDepth=6, AutomationId="CaptionsScrollViewer")
            
            while self.running and captions_window.Exists(0.1):
                try:
                    current_text = ""
                    if scroll_viewer.Exists(0.05):
                        current_text = scroll_viewer.Name or ""

                    current_text = clean_caption_text(current_text)

                    if current_text and current_text != self.last_text:
                        diff_text = current_text
                        if self.last_text and current_text.startswith(self.last_text):
                            diff_text = current_text[len(self.last_text):].strip()
                        elif len(current_text) > 120:
                            parts = re.split(r'[\.\?\!\n]', current_text)
                            non_empty = [p.strip() for p in parts if p.strip()]
                            if non_empty:
                                diff_text = non_empty[-1]

                        # التأكد من وجود جملة ذات معنى وليست مجرد حرف واحد
                        if diff_text and len(diff_text) >= 3:
                            ar_text = translate_english_to_arabic(diff_text)
                            if ar_text and contains_arabic(ar_text):
                                self.subtitle_ready.emit(diff_text, ar_text)
                        
                        self.last_text = current_text

                except Exception:
                    pass

                time.sleep(0.15)  # 150ms لتجميع الكلمات في جمل مفهومة وتجنب التكرار

            time.sleep(0.5)

        self.status_changed.emit("تم إيقاف مراقبة Windows Live Captions")
