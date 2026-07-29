@echo off
setlocal enabledelayedexpansion

rem One-shot setup for Windows: builds ContextCompresso, starts it, and
rem prints the config needed to point Claude Code or GitHub Copilot at it.

cd /d "%~dp0\.."

set PORT=8137
set BASE_URL=http://localhost:%PORT%

echo ==^> Checking prerequisites...

where mvn >nul 2>&1
if errorlevel 1 (
  echo !! 'mvn' not found on PATH. Install Maven and ensure it's on PATH, then try again.
  exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
  echo !! 'java' not found on PATH. Install a JDK 17+ and ensure it's on PATH, then try again.
  exit /b 1
)

java -version >nul 2>&1
if errorlevel 1 (
  echo !! 'java' is on PATH but failed to run. Check your JDK installation.
  exit /b 1
)

set JAVA_MAJOR=
for /f tokens^=3 %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
  set JAVA_RAW=%%~v
)
set JAVA_RAW=%JAVA_RAW:"=%
for /f "delims=. tokens=1" %%a in ("%JAVA_RAW%") do set JAVA_MAJOR=%%a
if "%JAVA_MAJOR%"=="1" (
  rem legacy 1.x versioning (e.g. 1.8) - treat as pre-17
  set JAVA_MAJOR=8
)
if defined JAVA_MAJOR (
  if %JAVA_MAJOR% LSS 17 (
    echo !! Detected Java %JAVA_RAW%, but ContextCompresso requires Java 17+.
    exit /b 1
  )
)

echo ==^> Building ContextCompresso (mvn clean package)...
call mvn -q clean package -DskipTests
if errorlevel 1 (
  echo Build failed.
  exit /b 1
)

echo.
echo Which client are you setting up ContextCompresso for?
echo   1) Claude Code
echo   2) GitHub Copilot
set /p CHOICE="Enter 1 or 2: "

echo.
echo ==^> Starting ContextCompresso on port %PORT%...
start "ContextCompresso" /min cmd /c "java -jar target\contextcompresso.jar > contextcompresso.log 2>&1"

echo ==^> Waiting for health check...
set /a COUNT=0

:waitloop
curl -s -f %BASE_URL%/actuator/health | findstr /C:"\"status\":\"UP\"" >nul
if %errorlevel%==0 goto up
set /a COUNT+=1
if %COUNT% GEQ 30 (
  echo Timed out waiting for %BASE_URL%/actuator/health - check contextcompresso.log
  exit /b 1
)
timeout /t 1 /nobreak >nul
goto waitloop

:up
echo ==^> ContextCompresso is UP.
echo.

if "%CHOICE%"=="1" (
  set ANTHROPIC_BASE_URL=%BASE_URL%

  if not exist ".vscode" mkdir ".vscode"
  if not exist ".vscode\settings.json" echo {} > ".vscode\settings.json"
  where python >nul 2>&1
  if errorlevel 1 (
    echo !! python not found, couldn't auto-update .vscode\settings.json.
    echo    Add this manually:
    echo    "terminal.integrated.env.windows": { "ANTHROPIC_BASE_URL": "%BASE_URL%" }
  ) else (
    python "%~dp0update_vscode_settings.py" ".vscode\settings.json" "%BASE_URL%"
    echo ==^> Wrote ANTHROPIC_BASE_URL to .vscode\settings.json
  )

  echo.
  echo ANTHROPIC_BASE_URL=%BASE_URL% is set for this session.
  echo To make it permanent for your user account, run:
  echo   setx ANTHROPIC_BASE_URL "%BASE_URL%"
  echo ^(then open a new terminal so it takes effect^)
) else (
  echo Point GitHub Copilot at it by adding this to your VS Code settings.json:
  echo.
  echo   "github.copilot.advanced": {
  echo     "debug.overrideProxyUrl": "%BASE_URL%"
  echo   }
)

echo.
echo ContextCompresso is running in a minimized window - close it to stop the server.

endlocal
