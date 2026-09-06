@echo off
setlocal
cd /d "%~dp0"

REM ================================================================
REM  javaHarness 一键启动器（Windows 本机版）
REM  需要 Windows 侧自备 JDK17 + Maven，且本仓库有一份 Windows 侧
REM  检出（本脚本按 %~dp0 相对路径找 .mvn\settings.xml）。
REM  项目/JDK/Maven 已迁 WSL 的话请用 run.bat（WSL 版）。
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
