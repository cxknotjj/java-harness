@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo   javaHarness 一键启动
echo   - 窗口1: 主服务 (http://localhost:8080)
echo   - 窗口2: CLI 聊天
echo ============================================
echo.

REM ---- 先编译一次，避免启动失败 ----
echo [1/3] 编译项目...
call mvn -s .mvn\settings.xml -DskipTests compile
if errorlevel 1 (
    echo [错误] 编译失败，请排错后重试。
    pause
    exit /b 1
)
echo 编译成功。
echo.

REM ---- 2. 启动主服务（新窗口）----
echo [2/3] 启动主服务...
start "javaHarness-server" cmd /k "cd /d %~dp0 && mvn -s .mvn\settings.xml spring-boot:run"
echo 主服务窗口已打开。
echo.

REM ---- 3. 等待主服务就绪 ----
echo [等待] 正在等待主服务启动 ^(最长 90 秒^)...
set cnt=0
:wait
set /a cnt+=1
if %cnt% gtr 90 (
    echo [提示] 主服务就绪超时，仍将尝试启动 CLI（若失败请稍后重跑）。
    goto startcli
)
timeout /t 1 /nobreak >nul
powershell -NoProfile -Command "try{\$r=Invoke-WebRequest -Uri 'http://localhost:8080/api/harness/agents' -TimeoutSec 2 -UseBasicParsing; exit 0}catch{exit 1}"
if %errorlevel% equ 0 (
    echo 主服务已就绪！
    goto startcli
)
goto :wait

:startcli
echo.
echo [启动] 打开 CLI 聊天窗口...
echo         直接输入文字聊天，/exit 退出。
start "javaHarness CLI" cmd /k "cd /d %~dp0 && mvn -q -s .mvn\settings.xml exec:java"

echo.
echo 已启动主服务与 CLI，两个窗口已打开。
echo 若 CLI 提示连接失败，等主服务就绪后重跑本脚本。
echo.
pause
endlocal