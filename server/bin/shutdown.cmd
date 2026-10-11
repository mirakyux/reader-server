@echo off
REM Windows source-checkout launcher; API and React are independent services.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\..\dev.ps1" stop %*
exit /b %errorlevel%
