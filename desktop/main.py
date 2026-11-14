import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import Qt

from config import load_config, save_config
from ui_overlay import SubtitleOverlay
from ui_control import ControlPanel
from audio_capture import AudioCaptureThread
from transcriber import TranscriberThread, translate_english_to_arabic
from livecaptions_hook import WindowsLiveCaptionsWatcher

class LiveTranslatorApp:
    def __init__(self):
        self.config = load_config()
        self.app = QApplication(sys.argv)
        self.app.setApplicationName("LiveVideoTranslator")

        self.overlay = SubtitleOverlay(self.config)
        self.overlay.show()

        self.control_panel = ControlPanel(self.config, self.overlay)
        self.control_panel.show()

        self.audio_thread = None
        self.transcriber_thread = None
        self.livecaptions_thread = None

        self.control_panel.start_requested.connect(self.start_translation)
        self.control_panel.stop_requested.connect(self.stop_translation)
        self.control_panel.settings_changed.connect(self.on_settings_saved)

    def start_translation(self):
        self.stop_translation()

        # 1. تشغيل مراقب ميزة ويندوز Live Captions (سرعة فورية وتأخير صفر!)
        self.livecaptions_thread = WindowsLiveCaptionsWatcher()
        self.livecaptions_thread.subtitle_ready.connect(self.on_subtitle_received)
        self.livecaptions_thread.status_changed.connect(self.control_panel.update_status)
        self.livecaptions_thread.start()

        # 2. تشغيل محرك التفريغ الصوتي (Whisper المحلي أو Groq السحابي)
        self.transcriber_thread = TranscriberThread(self.config)
        self.transcriber_thread.subtitle_ready.connect(self.on_subtitle_received)
        self.transcriber_thread.status_changed.connect(self.control_panel.update_status)
        self.transcriber_thread.start()

        # 3. تشغيل ملتقط صوت النظام السريع
        self.audio_thread = AudioCaptureThread(self.config)
        self.audio_thread.audio_segment_ready.connect(self.transcriber_thread.push_audio)
        self.audio_thread.audio_level_updated.connect(self.control_panel.update_audio_level)
        self.audio_thread.status_changed.connect(self.control_panel.update_status)
        self.audio_thread.start()

    def stop_translation(self):
        if self.livecaptions_thread and self.livecaptions_thread.isRunning():
            self.livecaptions_thread.stop()
            self.livecaptions_thread = None

        if self.audio_thread and self.audio_thread.isRunning():
            self.audio_thread.stop()
            self.audio_thread = None

        if self.transcriber_thread and self.transcriber_thread.isRunning():
            self.transcriber_thread.stop()
            self.transcriber_thread = None

        self.control_panel.update_audio_level(0.0)

    def on_subtitle_received(self, en_text: str, ar_text: str):
        self.overlay.display_subtitles(en_text, ar_text)
        self.control_panel.add_transcript_entry(en_text, ar_text)

    def on_settings_saved(self):
        save_config(self.config)

    def run(self):
        ret = self.app.exec()
        self.stop_translation()
        save_config(self.config)
        sys.exit(ret)

if __name__ == "__main__":
    app_instance = LiveTranslatorApp()
    app_instance.run()
