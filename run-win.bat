@echo off
setlocal
cd /d "%~dp0"

REM ================================================================
REM  javaHarness one-click launcher (Windows-native edition).
REM  Requires a Windows-side checkout of this repo plus Windows
REM  JDK17 + Maven on PATH (script resolves .mvn\settings.xml via %~dp0).
REM  For the WSL-based environment use run-wsl.bat instead.
REM  Run only ONE launcher at a time (port 8080 conflict).
REM  NOTE: keep this file ASCII-only (cmd parses .bat with the console
REM  codepage; multi-byte chars in a UTF-8 .bat break line parsing).
REM ================================================================

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
REM exec:java is not part of the compile lifecycle - compile must run first,
REM otherwise stale classes stay in target\classes (caused mojibake regression once)
start "java_harness_cli" cmd /k "cd /d %~dp0 && mvn -s .mvn\settings.xml compile exec:java"

echo.
echo Server and CLI launched. Two windows opened.
echo If CLI failed to connect, wait for server ready and rerun.
echo.
pause
endlocal
