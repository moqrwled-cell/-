@echo off
title Live Video Translator (Desktop)
cd /d "%~dp0desktop"
python main.py
if errorlevel 1 (
    echo.
    echo An error occurred. Make sure dependencies are installed: pip install -r requirements.txt
    pause
)
