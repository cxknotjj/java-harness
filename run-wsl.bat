@echo off
chcp 65001 >nul
title javaHarness (WSL)
setlocal
REM ================================================================
REM  javaHarness 一键启动器（WSL 版，Windows 侧双击或命令行运行）
REM  Windows 本机版是 run-win.bat：项目在 Windows 侧检出且装有
REM  Windows JDK/Maven 时用那个。两个脚本二选一，不要同时跑
REM  （8080 端口会冲突，且两边 target 目录互不相通）。
REM  项目/JDK17/Maven 全部位于 WSL 内，构建与运行经由 wsl.exe 完成：
REM    - 项目:  /home/wsl/development/java-harness
REM    - JDK17: /home/wsl/jdk-17.0.20.1+1   Maven: /home/wsl/apache-maven-3.9.9
REM  ~/.bashrc 里的 PATH 导出只对交互 shell 生效，非交互 bash 找不到
REM  mvn/java，因此每条命令都显式注入 JAVA_HOME/PATH。
REM  服务监听 WSL 的 8080，WSL2 localhost 转发 → Windows 直接可访问。
REM ================================================================
set "PROJ=/home/wsl/development/java-harness"
set "ENV=export JAVA_HOME=/home/wsl/jdk-17.0.20.1+1 && export PATH=$JAVA_HOME/bin:/home/wsl/apache-maven-3.9.9/bin:$PATH"

echo ============================================
echo   javaHarness One-Click Launcher (WSL)
echo   - Window 1: Server (http://localhost:8080)
echo   - Window 2: CLI Chat
echo ============================================
echo.

REM ---- 1. Compile first to avoid startup failure ----
echo [1/3] Compiling project (in WSL)...
wsl.exe -e bash -c "cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -DskipTests compile"
if errorlevel 1 (
    echo [ERROR] Compilation failed. Fix errors and retry.
    pause
    exit /b 1
)
echo Compilation OK.
echo.

REM ---- 2. Start server in a new window ----
echo [2/3] Starting server (in WSL)...
REM 先经 Windows 侧 cmd.exe 把该控制台切到 UTF-8 代码页，避免 WSL 里
REM Maven/Spring 的中文日志在 GBK 控制台乱码；失败不阻塞（分号续跑）
start "javaHarness-server" cmd /k wsl.exe -e bash -c "cmd.exe /c chcp 65001 2>/dev/null; cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml spring-boot:run"
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
REM CLI 用 exec:exec（独立 JVM 进程接管终端，JLine 历史/补全可用）；
REM -Pcli 生命周期里带 compile，避免旧 class 残留（曾导致乱码回归）
start "java_harness_cli" cmd /k wsl.exe -e bash -c "cmd.exe /c chcp 65001 2>/dev/null; cd '%PROJ%' && %ENV% && mvn -s .mvn/settings.xml -Pcli compile exec:exec"

echo.
echo Server and CLI launched. Two windows opened.
echo If CLI failed to connect, wait for server ready and rerun.
echo.
pause
endlocal
