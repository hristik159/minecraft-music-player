@echo off
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
 echo Install Python 3.11 or newer from python.org with Python Launcher enabled.
 pause
 exit /b 1
)
if not exist .venv\Scripts\python.exe py -3 -m venv .venv
.venv\Scripts\python.exe -m pip install --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
 echo Dependency installation failed. Check your internet connection.
 pause
 exit /b 1
)
.venv\Scripts\python.exe server.py
pause
