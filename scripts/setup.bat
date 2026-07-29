@echo off
setlocal enabledelayedexpansion

rem One-shot setup for Windows: builds ContextCompresso, starts it, and
rem prints the config needed to point Claude Code or GitHub Copilot at it.

cd /d "%~dp0\.."

set PORT=8137
set BASE_URL=http://localhost:%PORT%

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
  echo Point Claude Code at it for this shell session:
  echo.
  echo   set ANTHROPIC_BASE_URL=%BASE_URL%
  echo.
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
