@echo off
title Live Video Translator
cd /d "%~dp0"
echo Starting Live Video Translator...
python main.py
if errorlevel 1 (
    echo.
    echo An error occurred while running the application.
    pause
)
