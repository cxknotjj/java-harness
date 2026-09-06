#!/usr/bin/env bash
# ================================================================
#  javaHarness 启动脚本（WSL 内直接使用）
#
#  全流程（无参数）：
#    [1/4] 编译
#    [2/4] 开一个新窗口前台跑服务（日志直接看，Ctrl+C 即停服务）
#    [3/4] 当前终端轮询等就绪（最多 90s）
#    [4/4] 当前终端变成 CLI 聊天
#
#  Windows 侧一键启动请用 run-wsl.bat；两套入口选其一，勿同时跑服务。
#
#  子命令:
#    ./run.sh server   当前终端前台跑服务（Ctrl+C 停止）
#    ./run.sh stop     停止 8080 上的服务
#    ./run.sh cli      只进 CLI（服务需已在跑）
#    ./run.sh build    只编译
#    ./run.sh test     全量测试
# ================================================================
set -e
cd "$(dirname "$0")"

# 显式注入 JDK/Maven 路径：与非交互场景（cron/CI）兼容，交互 shell 重复注入无副作用
export JAVA_HOME="$HOME/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$HOME/apache-maven-3.9.9/bin:$PATH"

# 本地密钥（QWEN_API_KEY/DEEPSEEK_API_KEY）：Ubuntu 非交互 bash 不执行 .bashrc 的
# export（开头守卫 early-return），server 子命令与 nohup 兜底路径在此显式加载；
# 开窗路径环境不跨 wt.exe/wsl.exe 边界，由下方生成的窗口脚本再加载一次
if [ -f .env.local ]; then . ./.env.local; fi

PROJ="$(pwd)"
MVN="mvn -s .mvn/settings.xml"

port_listening() { ss -ltn 2>/dev/null | grep -q ':8080 '; }

# 开新窗口前台跑服务：优先 Windows Terminal，回退 cmd start；都失败则 nohup 后台兜底。
# 注意：启动逻辑必须写进脚本文件再让新窗口执行——wt.exe 会把命令行里的「;」当作
# 子命令分隔符（wt 支持链式语法），内联多段命令被拆散后弹出 0x80070002 找不到 "read _"
open_server_window() {
    local script=/tmp/javaHarness-server-window.sh
    cat > "$script" <<EOF
#!/usr/bin/env bash
cd '$PROJ'
# 新 wsl 会话不继承本 shell 环境，密钥在此独立加载（缺失则跳过，应用回退占位 key）
if [ -f .env.local ]; then . ./.env.local; fi
export JAVA_HOME="$JAVA_HOME"
export PATH="$PATH"
$MVN spring-boot:run
echo
echo '--- 服务已退出，按回车关闭窗口 ---'
read _
EOF
    chmod +x "$script"
    if command -v wt.exe >/dev/null 2>&1 \
        && wt.exe -w new nt --title "javaHarness-server" wsl.exe -e bash "$script" 2>/dev/null; then
        echo "服务窗口已打开（Windows Terminal）"
        return 0
    fi
    if cmd.exe /c start "javaHarness-server" wsl.exe -e bash "$script" 2>/dev/null; then
        echo "服务窗口已打开"
        return 0
    fi
    echo "[WARN] 无法开新窗口，服务转入后台模式（日志: /tmp/javaHarness-server.log，tail -f 查看）"
    nohup $MVN spring-boot:run > /tmp/javaHarness-server.log 2>&1 &
}

wait_ready() {
    printf "等待服务就绪（最多 90s）"
    for i in $(seq 1 90); do
        printf "."
        if curl -s -o /dev/null --max-time 2 http://localhost:8080/api/harness/agents; then
            echo ""
            echo "服务就绪（${i}s）"
            return 0
        fi
        sleep 1
    done
    echo ""
    echo "[WARN] 90s 内未就绪。看服务窗口日志，或 ./run.sh stop 后重试"
    return 1
}

case "${1:-}" in
  ""|full)
    echo "==== javaHarness 全流程启动 ===="
    echo "[1/4] 编译..."
    $MVN -DskipTests compile
    echo "[1/4] 编译 OK"
    if port_listening; then
        echo "[2/4] 服务已在运行，跳过启动"
    else
        echo "[2/4] 开服务窗口..."
        open_server_window
    fi
    echo "[3/4] 等待就绪..."
    if ! wait_ready; then
        echo "服务未就绪，CLI 不启动。"
        exit 1
    fi
    echo "[4/4] 进入 CLI 聊天（/exit 退出）"
    $MVN -Pcli compile exec:exec
    echo "--- CLI 已退出。服务窗口仍在运行（停止: 在该窗口 Ctrl+C 或 ./run.sh stop）---"
    ;;
  server)
    $MVN spring-boot:run
    ;;
  stop)
    fuser -k 8080/tcp 2>/dev/null || true
    # pkill 无匹配时返回 1，set -e 会当场退出脚本——必须 || true
    pkill -f 'spring-boot:ru[n]' 2>/dev/null || true
    # SIGKILL 后内核异步释放 socket，短暂重试避免撞上释放窗口
    for i in $(seq 1 5); do
        sleep 1
        port_listening || break
    done
    if port_listening; then
        echo "停止失败：8080 仍在监听"
        exit 1
    fi
    echo "服务已停止"
    ;;
  cli)
    $MVN -Pcli compile exec:exec
    ;;
  build)
    $MVN -DskipTests compile
    ;;
  test)
    $MVN test
    ;;
  *)
    echo "用法: ./run.sh [full|server|stop|cli|build|test]（无参数=全流程）"
    exit 1
    ;;
esac
