@echo off
setlocal EnableExtensions

rem Run Eagle from the repo (Gradle). Needs .env + config.json at project root.

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%\.." || (
  echo error: could not cd to project root >&2
  exit /b 1
)

if not exist "gradlew.bat" (
  echo error: gradlew.bat not found. Run from the eagle repo root. >&2
  exit /b 1
)

where java >nul 2>&1 || (
  echo error: Java not found. Install JDK 17+. >&2
  exit /b 1
)

if not exist ".env" (
  if exist ".env.example" (
    copy /Y ".env.example" ".env" >nul
    echo Created .env from .env.example - set TELEGRAM_BOT_TOKEN.
  ) else (
    echo error: missing .env and .env.example >&2
    exit /b 1
  )
)

if not exist "config.json" (
  if exist "config.example.json" (
    copy /Y "config.example.json" "config.json" >nul
    echo Created config.json from config.example.json - edit as needed.
  ) else (
    echo error: missing config.json and config.example.json >&2
    exit /b 1
  )
)

echo ==^> Starting Eagle ^(Ctrl+C to stop^)...
call gradlew.bat run
set "EXIT=%ERRORLEVEL%"
exit /b %EXIT%
