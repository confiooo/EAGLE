@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build Eagle (fat jar). Optional: copy jar + config into a directory.
rem   scripts\setup.bat
rem   scripts\setup.bat --install "C:\Path\Eagle"

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%\.." || (
  echo error: could not cd to project root >&2
  exit /b 1
)
set "PROJECT_ROOT=%CD%"

set "INSTALL_DIR="
:parseArgs
if "%~1"=="" goto argsDone
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
if /i "%~1"=="--install" (
  if "%~2"=="" (
    echo error: --install needs a directory >&2
    exit /b 1
  )
  set "INSTALL_DIR=%~2"
  shift
  shift
  goto parseArgs
)
echo error: unknown option: %~1 >&2
goto usage

:usage
echo Usage:
echo   scripts\setup.bat
echo   scripts\setup.bat --install "C:\Path\Eagle"
exit /b 1

:argsDone

if not exist "gradlew.bat" (
  echo error: gradlew.bat not found in "%PROJECT_ROOT%" >&2
  exit /b 1
)

where java >nul 2>&1 || (
  echo error: Java not found. Install JDK 17+.
  exit /b 1
)

echo ==^> Building jar... ^(JDK 17+ required - Gradle will fail if the JDK is wrong^)
call gradlew.bat --no-daemon jar
if errorlevel 1 (
  echo error: build failed >&2
  exit /b 1
)

set "JAR="
for %%f in ("%PROJECT_ROOT%\build\libs\eagle-*.jar") do (
  set "fname=%%~nxf"
  echo !fname! | findstr /i /c:"-plain" >nul
  if errorlevel 1 set "JAR=%%f"
)

if not defined JAR (
  echo error: could not find build\libs\eagle-*.jar ^(non-plain^) >&2
  exit /b 1
)
if not exist "!JAR!" (
  echo error: jar not found: !JAR! >&2
  exit /b 1
)

echo ==^> Built: !JAR!

if "!INSTALL_DIR!"=="" (
  echo.
  echo Done. Run the bot:
  echo   scripts\run-bot.bat
  echo Or: java -jar "!JAR!"
  exit /b 0
)

echo ==^> Installing to "!INSTALL_DIR!" ...
if not exist "!INSTALL_DIR!" mkdir "!INSTALL_DIR!"
copy /Y "!JAR!" "!INSTALL_DIR!\eagle.jar" >nul
if errorlevel 1 (
  echo error: could not copy jar >&2
  exit /b 1
)

if not exist "!INSTALL_DIR!\config.json" (
  copy /Y "%PROJECT_ROOT%\config.example.json" "!INSTALL_DIR!\config.json" >nul
  echo   copied config.json from config.example.json
) else (
  echo   kept existing config.json
)

if not exist "!INSTALL_DIR!\.env" (
  copy /Y "%PROJECT_ROOT%\.env.example" "!INSTALL_DIR!\.env" >nul
  echo   copied .env from .env.example - set TELEGRAM_BOT_TOKEN
) else (
  echo   kept existing .env
)

echo.
echo Install complete. Run:
echo   cd /d "!INSTALL_DIR!"
echo   java -jar eagle.jar
exit /b 0
