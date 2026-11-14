from PyQt6.QtWidgets import QWidget, QVBoxLayout, QLabel, QGraphicsDropShadowEffect
from PyQt6.QtCore import Qt, QPoint, QTimer
from PyQt6.QtGui import QFont, QColor
from transcriber import contains_arabic

class SubtitleOverlay(QWidget):
    def __init__(self, config):
        super().__init__()
        self.config = config
        self.drag_position = QPoint()
        self.is_locked = False

        self.init_window_flags()
        self.init_ui()
        self.apply_geometry()

        # مؤقت لمسح الترجمة تلقائياً بعد 6 ثوانٍ من الصمت
        self.clear_timer = QTimer(self)
        self.clear_timer.setSingleShot(True)
        self.clear_timer.timeout.connect(self.clear_subtitles)

    def init_window_flags(self):
        flags = (
            Qt.WindowType.FramelessWindowHint |
            Qt.WindowType.WindowStaysOnTopHint |
            Qt.WindowType.SubWindow
        )
        if self.is_locked:
            flags |= Qt.WindowType.WindowTransparentForInput

        self.setWindowFlags(flags)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)

    def init_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(15, 8, 15, 8)
        layout.setSpacing(4)

        # 1. النص الإنجليزي الأصلي (أبيض / رمادي فاتح)
        self.en_label = QLabel("", self)
        self.en_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.en_label.setWordWrap(True)

        # 2. الترجمة العربية الرئيسية (أصفر واضح دائماً)
        self.ar_label = QLabel("شريط الترجمة الفورية جاهز...", self)
        self.ar_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.ar_label.setWordWrap(True)

        shadow = QGraphicsDropShadowEffect(self)
        shadow.setBlurRadius(8)
        shadow.setColor(QColor(0, 0, 0, 240))
        shadow.setOffset(2, 2)
        self.ar_label.setGraphicsEffect(shadow)

        layout.addWidget(self.en_label)
        layout.addWidget(self.ar_label)

        self.update_styles()

    def update_styles(self):
        font_size = int(self.config.get("font_size", 22))
        text_color = self.config.get("text_color", "#FFEB3B")
        bg_opacity = int(self.config.get("bg_opacity", 190))
        display_mode = self.config.get("display_mode", "bilingual")

        # الخط العربي (اللون الأصفر المميز)
        self.ar_label.setFont(QFont("Segoe UI", font_size, QFont.Weight.Bold))
        self.ar_label.setStyleSheet(f"color: {text_color}; background: transparent;")

        # الخط الإنجليزي (لون أبيض/رمادي ثانوي)
        en_font_size = max(font_size - 6, 12)
        self.en_label.setFont(QFont("Segoe UI", en_font_size, QFont.Weight.DemiBold))
        self.en_label.setStyleSheet("color: #E0E0E0; background: transparent;")
        self.en_label.setVisible(display_mode == "bilingual")

        self.setStyleSheet(f"""
            QWidget#SubtitleOverlay {{
                background-color: rgba(18, 18, 22, {bg_opacity});
                border-radius: 12px;
                border: 1px solid rgba(255, 255, 255, 30);
            }}
        """)
        self.setObjectName("SubtitleOverlay")

    def apply_geometry(self):
        w = int(self.config.get("window_width", 860))
        h = int(self.config.get("window_height", 95))
        x = int(self.config.get("window_x", -1))
        y = int(self.config.get("window_y", -1))

        if x < 0 or y < 0:
            screen = self.screen().geometry()
            x = (screen.width() - w) // 2
            y = screen.height() - h - 80

        self.setGeometry(x, y, w, h)

    def display_subtitles(self, en_text: str, ar_text: str):
        """
        عرض الترجمة مع ضمان صارم بأن النص الأصفر هو العربي دائماً
        """
        final_ar = ""
        final_en = ""

        # التأكد التام من وضع النص العربي في ar_label الأصفر
        if ar_text and contains_arabic(ar_text):
            final_ar = ar_text
            final_en = en_text
        elif en_text and contains_arabic(en_text):
            final_ar = en_text
            final_en = ar_text
        elif ar_text:
            # إذا تعذرت الترجمة مؤقتاً، لا نضع الإنجليزي في مكان الأصفر إلا إذا لم يكن هناك بديل
            final_ar = ar_text

        if final_ar:
            self.ar_label.setText(final_ar)

        if self.config.get("display_mode") == "bilingual" and final_en:
            self.en_label.setText(final_en)
            self.en_label.show()
        else:
            self.en_label.hide()

        self.clear_timer.start(6500)

    def clear_subtitles(self):
        self.ar_label.setText("")
        self.en_label.setText("")

    def set_click_through(self, locked: bool):
        self.is_locked = locked
        self.config["click_through"] = locked
        self.hide()
        self.init_window_flags()
        self.show()

    def mousePressEvent(self, event):
        if not self.is_locked and event.button() == Qt.MouseButton.LeftButton:
            self.drag_position = event.globalPosition().toPoint() - self.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event):
        if not self.is_locked and event.buttons() == Qt.MouseButton.LeftButton:
            new_pos = event.globalPosition().toPoint() - self.drag_position
            self.move(new_pos)
            self.config["window_x"] = new_pos.x()
            self.config["window_y"] = new_pos.y()
            event.accept()
