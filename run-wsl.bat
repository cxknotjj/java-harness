@echo off
chcp 65001 >nul
title javaHarness (WSL)
setlocal
REM ================================================================
REM  javaHarness launcher (WSL edition, single-console flow).
REM  Run from Windows: double-click, or run in cmd/PowerShell.
REM
REM  Flow (all in ONE console window):
REM    [1/4] compile inside WSL
REM    [2/4] start the server DETACHED inside WSL (nohup, background);
REM          its logs go to /tmp/javaHarness-server.log (watch: run-wsl.bat log)
REM    [3/4] poll http://localhost:8080 until ready (max 90s)
REM    [4/4] THIS console turns into the CLI chat (full TTY).
REM
REM  NOTE: we deliberately do NOT use "start ... wsl.exe" to open extra
REM  windows -- in real double-click runs it repeatedly failed to detach
REM  (server output leaked into the launcher console). The detached-
REM  server + single-console design has no such dependency.
REM
REM  Server ops (from cmd or WSL):
REM    run-wsl.bat server   start detached + tail -f the log
REM    run-wsl.bat log      watch server log (Ctrl+C stops watching only)
REM    run-wsl.bat stop     stop the server (kills the 8080 listener)
REM    run-wsl.bat cli      CLI chat only
REM    run-wsl.bat build    compile only
REM    run-wsl.bat test     full test suite
REM
REM  Environment (WSL side):
REM    project: /home/wsl/development/java-harness
REM    JDK17:   /home/wsl/jdk-17.0.20.1+1   Maven: /home/wsl/apache-maven-3.9.9
REM  Non-interactive bash does not source ~/.bashrc, so JAVA_HOME/PATH
REM  are injected explicitly on every command below.
REM
REM  NOTE: keep this file ASCII-only. cmd parses .bat lines with the
REM  console codepage; multi-byte chars in a UTF-8 .bat break parsing.
REM ================================================================
set "PROJ=/home/wsl/development/java-harness"
set "ENV=export JAVA_HOME=/home/wsl/jdk-17.0.20.1+1 && export PATH=$JAVA_HOME/bin:/home/wsl/apache-maven-3.9.9/bin:$PATH"
set "SRVLOG=/tmp/javaHarness-server.log"

if /i "%~1"=="server" goto server
if /i "%~1"=="stop"   goto stop
if /i "%~1"=="log"    goto log
if /i "%~1"=="cli"    goto cli
if /i "%~1"=="build"  goto build
if /i "%~1"=="test"   goto test

echo ============================================
echo   javaHarness Launcher (WSL, single console)
echo   Server runs in background (WSL), CLI takes
echo   over this window when the server is ready.
echo ============================================
echo.

echo [1/4] Compiling (in WSL)...
wsl.exe -e bash -c "cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -DskipTests compile"
if errorlevel 1 (
    echo [ERROR] Compilation failed. Fix errors and retry.
    pause
    exit /b 1
)
echo [1/4] Compilation OK.
echo.

echo [2/4] Starting server in background (inside WSL)...
wsl.exe -e bash -c "if ss -ltn 2>/dev/null | grep -q ':8080 '; then echo 'ALREADY: server is running, skip starting.'; else cd '%PROJ%' && %ENV% && nohup mvn -s .mvn/settings.xml spring-boot:run > %SRVLOG% 2>&1 & echo 'STARTED: server launching in background.'; fi"
echo        Server log: %SRVLOG% (in WSL; watch with: run-wsl.bat log)
echo.

echo [3/4] Waiting for server (max 90s, one dot per second)...
set cnt=0
:wait
set /a cnt+=1
if %cnt% gtr 90 (
    echo.
    echo [WARN] Server not ready within 90s. Check the log: run-wsl.bat log
    echo        CLI will NOT start automatically this time.
    goto endwait
)
timeout /t 1 /nobreak >nul
<nul set /p "=."
powershell -NoProfile -Command "try{$r=Invoke-WebRequest -Uri 'http://localhost:8080/api/harness/agents' -TimeoutSec 2 -UseBasicParsing; exit 0}catch{exit 1}"
if %errorlevel% equ 0 goto ready
goto :wait
:ready
echo.
echo [3/4] Server is ready!
goto endwait
:endwait
echo.

echo [4/4] Starting CLI chat in THIS window...
echo        (server keeps running in background; stop later: run-wsl.bat stop)
echo        Type text to chat, /exit to quit.
echo.
wsl.exe -e bash -c "cmd.exe /c chcp 65001 2>/dev/null; cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -Pcli compile exec:exec; echo; echo '--- CLI exited. Server still runs in background. ---'; echo '    stop it: run-wsl.bat stop | watch log: run-wsl.bat log'"
echo.
pause
endlocal
exit /b 0

REM ---------------- single-command modes ----------------
:server
wsl.exe -e bash -c "if ss -ltn 2>/dev/null | grep -q ':8080 '; then echo 'Server already running.'; else cd '%PROJ%' && %ENV% && nohup mvn -s .mvn/settings.xml spring-boot:run > %SRVLOG% 2>&1 & echo 'Server started in background.'; fi"
echo Watching server log (Ctrl+C stops watching; server keeps running)...
wsl.exe -e bash -c "touch %SRVLOG%; tail -f %SRVLOG%"
goto :eof

:stop
wsl.exe -e bash -c "fuser -k 8080/tcp 2>/dev/null; pkill -f 'spring-boot:ru[n]' 2>/dev/null; sleep 1; if ss -ltn 2>/dev/null | grep -q ':8080 '; then echo 'STOP FAILED: port 8080 still listening.'; else echo 'Server stopped.'; fi"
goto :eof

:log
wsl.exe -e bash -c "touch %SRVLOG%; tail -f %SRVLOG%"
goto :eof

:cli
wsl.exe -e bash -c "cmd.exe /c chcp 65001 2>/dev/null; cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -Pcli compile exec:exec"
goto :eof

:build
wsl.exe -e bash -c "cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -DskipTests compile"
goto :eof

:test
wsl.exe -e bash -c "cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml test"
goto :eof
