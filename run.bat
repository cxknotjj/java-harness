@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   javaHarness One-Click Launcher
echo   - Window 1: Server (http://localhost:8080)
echo   - Window 2: CLI Chat
echo ============================================
echo.

REM ---- 1. Compile first to avoid startup failure ----
echo [1/3] Compiling project...
call mvn -s .mvn\settings.xml -DskipTests compile
if errorlevel 1 (
    echo [ERROR] Compilation failed. Fix errors and retry.
    pause
    exit /b 1
)
echo Compilation OK.
echo.

REM ---- 2. Start server in a new window ----
echo [2/3] Starting server...
start "javaHarness-server" cmd /k "cd /d %~dp0 && mvn -s .mvn\settings.xml spring-boot:run"
echo Server window opened.
echo.

REM ---- 3. Wait for server to be ready ----
echo [WAIT] Waiting for server to start (max 90s)...
set cnt=0
:wait
set /a cnt+=1
if %cnt% gtr 90 (
    echo [WARN] Server not confirmed ready. Still opening CLI.
    goto startcli
)
timeout /t 1 /nobreak >nul
powershell -NoProfile -Command "try{$r=Invoke-WebRequest -Uri 'http://localhost:8080/api/harness/agents' -TimeoutSec 2 -UseBasicParsing; exit 0}catch{exit 1}"
if %errorlevel% equ 0 (
    echo Server is ready!
    goto startcli
)
goto :wait

:startcli
echo.
echo Opening CLI chat window...
echo Type text to chat, /exit to quit.
start "java_harness_cli" cmd /k "cd /d %~dp0 && mvn -q -s .mvn\settings.xml exec:java"

echo.
echo Server and CLI launched. Two windows opened.
echo If CLI failed to connect, wait for server ready and rerun.
echo.
pause
endlocal