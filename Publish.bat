@echo off
REM ============================================================
REM  AppBlocker — one-click publisher
REM  Just double-click this file to publish a new version.
REM ============================================================
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish.ps1"
echo.
pause
