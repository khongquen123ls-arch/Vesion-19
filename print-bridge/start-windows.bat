@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
 echo Chua cai Node.js. Hay cai Node.js LTS roi chay lai.
 pause
 exit /b 1
)
node server.js
pause
