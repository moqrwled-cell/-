import time
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QPushButton, QComboBox, QSlider, QCheckBox,
    QProgressBar, QTextEdit, QGroupBox, QLineEdit
)
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QFont

from transcriber import contains_arabic

class ControlPanel(QMainWindow):
    start_requested = pyqtSignal()
    stop_requested = pyqtSignal()
    settings_changed = pyqtSignal()

    def __init__(self, config, overlay):
        super().__init__()
        self.config = config
        self.overlay = overlay
        self.is_running = False

        self.setWindowTitle("مترجم الفيديوهات الفوري - Live Video Translator")
        self.resize(540, 720)
        self.setLayoutDirection(Qt.LayoutDirection.RightToLeft)

        self.init_ui()
        self.apply_dark_theme()

    def init_ui(self):
        central = QWidget(self)
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setContentsMargins(18, 18, 18, 18)
        main_layout.setSpacing(12)

        # 1. شريط التحكم الرئيسي (بدء / إيقاف)
        control_group = QGroupBox("حالة التشغيل والتحكم")
        control_layout = QVBoxLayout(control_group)

        btn_row = QHBoxLayout()
        self.btn_toggle = QPushButton("▶️ بدء الترجمة الفورية")
        self.btn_toggle.setFont(QFont("Segoe UI", 12, QFont.Weight.Bold))
        self.btn_toggle.setMinimumHeight(44)
        self.btn_toggle.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_toggle.clicked.connect(self.toggle_running)
        btn_row.addWidget(self.btn_toggle)

        self.btn_overlay_toggle = QPushButton("👁️ إخفاء/إظهار الشريط")
        self.btn_overlay_toggle.setFont(QFont("Segoe UI", 10))
        self.btn_overlay_toggle.setMinimumHeight(44)
        self.btn_overlay_toggle.setCursor(Qt.CursorShape.PointingHandCursor)
        self.btn_overlay_toggle.clicked.connect(self.toggle_overlay)
        btn_row.addWidget(self.btn_overlay_toggle)

        control_layout.addLayout(btn_row)

        # مؤشر الصوت المباشر
        level_row = QHBoxLayout()
        level_lbl = QLabel("صوت النظام:")
        level_lbl.setFont(QFont("Segoe UI", 9))
        self.audio_meter = QProgressBar()
        self.audio_meter.setRange(0, 100)
        self.audio_meter.setValue(0)
        self.audio_meter.setTextVisible(False)
        self.audio_meter.setFixedHeight(12)
        level_row.addWidget(level_lbl)
        level_row.addWidget(self.audio_meter)
        control_layout.addLayout(level_row)

        self.lbl_status = QLabel("الحالة: جاهز للبدء")
        self.lbl_status.setFont(QFont("Segoe UI", 9, QFont.Weight.DemiBold))
        self.lbl_status.setStyleSheet("color: #00E676;")
        control_layout.addWidget(self.lbl_status)

        main_layout.addWidget(control_group)

        # 2. وضع المعالجة والسرعة (Speed Engine)
        engine_group = QGroupBox("محرك السرعة الفائقة وتقليل التأخير ⚡")
        engine_layout = QVBoxLayout(engine_group)

        tip_lbl = QLabel("💡 نصيحة لسرعة لحظية بدون أي تأخير (0ms): فعّل ميزة ويندوز بالضغط على <b>Win + Ctrl + L</b> وسيرتبط التطبيق بها تلقائياً!")
        tip_lbl.setWordWrap(True)
        tip_lbl.setStyleSheet("color: #FFD54F; font-size: 11px;")
        engine_layout.addWidget(tip_lbl)

        # مفتاح Groq السحابي الاختياري
        groq_row = QHBoxLayout()
        groq_lbl = QLabel("مفتاح Groq (اختياري لسرعة 0.15s):")
        groq_lbl.setStyleSheet("font-size: 11px;")
        self.txt_groq = QLineEdit()
        self.txt_groq.setPlaceholderText("gsk_... (احصل عليه مجاناً من console.groq.com)")
        self.txt_groq.setText(self.config.get("groq_api_key", ""))
        self.txt_groq.textChanged.connect(self.on_groq_key_changed)
        groq_row.addWidget(groq_lbl)
        groq_row.addWidget(self.txt_groq)
        engine_layout.addLayout(groq_row)

        main_layout.addWidget(engine_group)

        # 3. تخصيص مظهر شريط الترجمة
        style_group = QGroupBox("تخصيص شريط الترجمة على الشاشة")
        style_grid = QGridLayout(style_group)
        style_grid.setSpacing(8)

        # نمط العرض
        style_grid.addWidget(QLabel("نمط العرض:"), 0, 0)
        self.combo_mode = QComboBox()
        self.combo_mode.addItem("ثنائي اللغة (عربي + إنجليزي)", "bilingual")
        self.combo_mode.addItem("عربي فقط (Arabic Only)", "ar_only")
        mode_idx = self.combo_mode.findData(self.config.get("display_mode", "bilingual"))
        if mode_idx >= 0:
            self.combo_mode.setCurrentIndex(mode_idx)
        self.combo_mode.currentIndexChanged.connect(self.on_display_mode_changed)
        style_grid.addWidget(self.combo_mode, 0, 1)

        # حجم الخط
        style_grid.addWidget(QLabel("حجم الخط:"), 1, 0)
        font_row = QHBoxLayout()
        self.slider_font = QSlider(Qt.Orientation.Horizontal)
        self.slider_font.setRange(16, 36)
        self.slider_font.setValue(int(self.config.get("font_size", 22)))
        self.lbl_font_val = QLabel(f"{self.slider_font.value()} px")
        self.slider_font.valueChanged.connect(self.on_font_changed)
        font_row.addWidget(self.slider_font)
        font_row.addWidget(self.lbl_font_val)
        style_grid.addLayout(font_row, 1, 1)

        # عتامة الخلفية
        style_grid.addWidget(QLabel("عتامة الخلفية:"), 2, 0)
        opac_row = QHBoxLayout()
        self.slider_opac = QSlider(Qt.Orientation.Horizontal)
        self.slider_opac.setRange(50, 255)
        self.slider_opac.setValue(int(self.config.get("bg_opacity", 190)))
        self.lbl_opac_val = QLabel(f"{int(self.slider_opac.value() / 255 * 100)}%")
        self.slider_opac.valueChanged.connect(self.on_opac_changed)
        opac_row.addWidget(self.slider_opac)
        opac_row.addWidget(self.lbl_opac_val)
        style_grid.addLayout(opac_row, 2, 1)

        # لون النص
        style_grid.addWidget(QLabel("لون الترجمة:"), 3, 0)
        colors = [
            ("أصفر ساطع", "#FFEB3B"),
            ("أبيض ناصع", "#FFFFFF"),
            ("سماوي Cyan", "#00E5FF"),
            ("أخضر ليموني", "#76FF03")
        ]
        self.combo_color = QComboBox()
        for label, hex_code in colors:
            self.combo_color.addItem(label, hex_code)
        c_idx = self.combo_color.findData(self.config.get("text_color", "#FFEB3B"))
        if c_idx >= 0:
            self.combo_color.setCurrentIndex(c_idx)
        self.combo_color.currentIndexChanged.connect(self.on_color_changed)
        style_grid.addWidget(self.combo_color, 3, 1)

        # قفل موضع الشريط (Click-Through)
        self.chk_lock = QCheckBox("قفل موضع الشريط وتفعيل النقر من خلاله (Click-Through)")
        self.chk_lock.setChecked(bool(self.config.get("click_through", False)))
        self.chk_lock.toggled.connect(self.on_lock_toggled)
        style_grid.addWidget(self.chk_lock, 4, 0, 1, 2)

        main_layout.addWidget(style_group)

        # 4. سجل الترجمات الحية
        hist_group = QGroupBox("سجل النصوص المترجمة")
        hist_layout = QVBoxLayout(hist_group)
        self.txt_history = QTextEdit()
        self.txt_history.setReadOnly(True)
        self.txt_history.setFont(QFont("Segoe UI", 9))
        self.txt_history.setPlaceholderText("ستظهر هنا الجمل المترجمة تباعاً مع الوقت...")
        hist_layout.addWidget(self.txt_history)
        main_layout.addWidget(hist_group)

    def apply_dark_theme(self):
        self.setStyleSheet("""
            QMainWindow {
                background-color: #121216;
            }
            QGroupBox {
                color: #FFFFFF;
                font-weight: bold;
                border: 1px solid #2B2D38;
                border-radius: 8px;
                margin-top: 10px;
                padding-top: 14px;
            }
            QGroupBox::title {
                subcontrol-origin: margin;
                subcontrol-position: top right;
                padding: 0 8px;
            }
            QLabel {
                color: #B0B3C1;
            }
            QPushButton {
                background-color: #2962FF;
                color: #FFFFFF;
                border: none;
                border-radius: 6px;
                padding: 6px 14px;
            }
            QPushButton:hover {
                background-color: #1E88E5;
            }
            QComboBox, QLineEdit {
                background-color: #1F2026;
                color: #FFFFFF;
                border: 1px solid #333644;
                border-radius: 6px;
                padding: 5px 8px;
            }
            QSlider::groove:horizontal {
                height: 6px;
                background: #252733;
                border-radius: 3px;
            }
            QSlider::sub-page:horizontal {
                background: #2962FF;
                border-radius: 3px;
            }
            QSlider::handle:horizontal {
                background: #FFFFFF;
                width: 14px;
                margin-top: -4px;
                margin-bottom: -4px;
                border-radius: 7px;
            }
            QProgressBar {
                background-color: #1F2026;
                border: 1px solid #333644;
                border-radius: 6px;
            }
            QProgressBar::chunk {
                background-color: #00E676;
                border-radius: 5px;
            }
            QCheckBox {
                color: #FFFFFF;
            }
            QCheckBox::indicator {
                width: 16px;
                height: 16px;
                border-radius: 4px;
                border: 1px solid #333644;
                background: #1F2026;
            }
            QCheckBox::indicator:checked {
                background: #2962FF;
            }
            QTextEdit {
                background-color: #181920;
                color: #E0E0E0;
                border: 1px solid #2B2D38;
                border-radius: 6px;
                padding: 6px;
            }
        """)

    def toggle_running(self):
        if not self.is_running:
            self.is_running = True
            self.btn_toggle.setText("⏹️ إيقاف الترجمة")
            self.btn_toggle.setStyleSheet("background-color: #D32F2F;")
            self.txt_groq.setEnabled(False)
            self.lbl_status.setText("الحالة: جاري الاستماع وترجمة الفيديو...")
            self.lbl_status.setStyleSheet("color: #00E676;")
            self.start_requested.emit()
        else:
            self.is_running = False
            self.btn_toggle.setText("▶️ بدء الترجمة الفورية")
            self.btn_toggle.setStyleSheet("background-color: #2962FF;")
            self.txt_groq.setEnabled(True)
            self.lbl_status.setText("الحالة: متوقف")
            self.lbl_status.setStyleSheet("color: #FFC107;")
            self.stop_requested.emit()

    def toggle_overlay(self):
        if self.overlay.isVisible():
            self.overlay.hide()
            self.btn_overlay_toggle.setText("👁️ إظهار الشريط")
        else:
            self.overlay.show()
            self.btn_overlay_toggle.setText("👁️ إخفاء الشريط")

    def update_audio_level(self, level: float):
        self.audio_meter.setValue(int(level * 100))

    def update_status(self, text: str):
        self.lbl_status.setText(f"الحالة: {text}")

    def add_transcript_entry(self, en_text: str, ar_text: str):
        if contains_arabic(en_text) and not contains_arabic(ar_text):
            en_text, ar_text = ar_text, en_text
        if not contains_arabic(ar_text):
            return
        timestamp = time.strftime("%H:%M:%S")
        entry = f"<span style='color:#9E9E9E;'>[{timestamp}]</span> <b style='color:#FFEB3B;'>{ar_text}</b><br><span style='color:#BDBDBD; font-size:11px;'>&nbsp;&nbsp;↳ {en_text}</span><br>"
        self.txt_history.append(entry)

    def on_groq_key_changed(self, text):
        self.config["groq_api_key"] = text.strip()
        self.settings_changed.emit()

    def on_display_mode_changed(self):
        self.config["display_mode"] = self.combo_mode.currentData()
        self.overlay.update_styles()
        self.settings_changed.emit()

    def on_font_changed(self, val):
        self.lbl_font_val.setText(f"{val} px")
        self.config["font_size"] = val
        self.overlay.update_styles()
        self.settings_changed.emit()

    def on_opac_changed(self, val):
        self.lbl_opac_val.setText(f"{int(val / 255 * 100)}%")
        self.config["bg_opacity"] = val
        self.overlay.update_styles()
        self.settings_changed.emit()

    def on_color_changed(self):
        self.config["text_color"] = self.combo_color.currentData()
        self.overlay.update_styles()
        self.settings_changed.emit()

    def on_lock_toggled(self, checked):
        self.overlay.set_click_through(checked)
        self.settings_changed.emit()

    def closeEvent(self, event):
        if self.is_running:
            self.stop_requested.emit()
        self.overlay.close()
        event.accept()
