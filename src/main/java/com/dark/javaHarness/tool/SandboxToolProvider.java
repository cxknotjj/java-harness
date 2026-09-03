package com.dark.javaHarness.tool;

import com.alibaba.cloud.ai.sandbox.ToolkitInit;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.box.BrowserSandbox;
import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 容器级沙箱工具提供者（spring-ai-alibaba-sandbox / agentscope-runtime）。
 *
 * <p>职责：把 agentscope 沙箱里的 ToolCallback（Python 执行 / Shell / 文件读写检索 /
 * 浏览器导航与快照）按安全类别暴露给 {@link ToolAssignments}，替代已退役的宿主机工具
 * （FileTools/SearchTools/ShellTools——模型生成的代码与命令只在容器内执行，宿主机零暴露）。
 *
 * <p>容器拓扑：同一个 {@link SandboxService} 管理两个容器（同 user/session，不同镜像类型）：
 * base 容器（Python/Shell/文件）与 browser 容器（Chromium 浏览器，独立镜像）。
 *
 * <p>初始化策略（架构决策：沙箱是硬依赖、不做宿主机降级）：
 * 懒初始化（首次取用才拉起 Docker 容器）+ 专用后台线程限时初始化（默认 15s）——
 * Docker 未运行时 agentscope 的 Docker 发现会回退到 Windows 命名管道且底层无超时、
 * 可能永久阻塞，限时等待保证请求线程绝不被拖死；超时/失败时沙箱工具面为空、
 * 进程内不再重试——即无 Docker 环境下沙箱类能力整体不可用，但应用其余功能不受影响；
 * 绝不回退到宿主机执行（文件/Shell 工具已删除，无退路可走）。
 * base 与 browser 两组初始化相互独立：浏览器镜像缺失只降级浏览器工具，不影响执行/文件类。
 */
@Component
public class SandboxToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolProvider.class);

    /** 沙箱内固定的用户/会话标识（容器命名与日志归因） */
    private static final String SANDBOX_USER = "javaHarness";
    private static final String SANDBOX_SESSION = "harness";

    private final Object lock = new Object();
    private volatile boolean initialized;

    /** 沙箱初始化专用单线程（守护线程）：Docker 管道阻塞时也不拖垮请求线程池 */
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sandbox-init");
        t.setDaemon(true);
        return t;
    });
    /** 一次性初始化任务句柄：预热线程与请求线程共用，保证 init 全进程只跑一次 */
    private volatile Future<?> initTask;

    /**
     * 初始化超时上限。Docker 未运行时 agentscope 的 Docker 发现会回退到 Windows
     * 命名管道（\\.\pipe\docker_engine），该连接底层无超时、可能永久阻塞（实测挂死
     * 3 分钟以上），必须由本层兜底超时，超时后请求按"无沙箱工具"继续。
     */
    volatile long initTimeoutMs = Duration.ofSeconds(15).toMillis();

    /** 浏览器组独立初始化锁：与 base 组互不阻塞、互不牵连 */
    private final Object browserLock = new Object();
    private volatile boolean browserInitialized;

    volatile List<ToolCallback> base = List.of();
    volatile List<ToolCallback> readOnly = List.of();
    volatile List<ToolCallback> write = List.of();
    private volatile List<ToolCallback> browser = List.of();
    private volatile SandboxService service;

    /** 执行类：Python 代码执行 + Shell 命令（base 容器内） */
    public List<ToolCallback> baseTools() {
        ensure();
        return base;
    }

    /** 只读文件类：读文件/批量读/列目录/目录树/搜索/文件信息（base 容器内文件系统） */
    public List<ToolCallback> readOnlyFileTools() {
        ensure();
        return readOnly;
    }

    /** 写入类：写文件/编辑替换/建目录/移动（base 容器内文件系统） */
    public List<ToolCallback> writeTools() {
        ensure();
        return write;
    }

    /**
     * 浏览器类：页面导航 / 无障碍快照（抓 JS 渲染后正文）/ 点击 / 输入 / 关闭
     * （browser 容器，独立镜像 runtime-sandbox-browser，缺失时本组为空）
     */
    public List<ToolCallback> browserTools() {
        ensureBrowser();
        return browser;
    }

    /**
     * 懒初始化 base 组：init 在专用后台线程执行。
     * 首次触发时启动 init 任务并限时等待（默认 15s）：Docker 不可用时 agentscope 会
     * 阻塞在命名管道连接上（无底层超时），限时等待保证请求线程永远不被拖死。
     * 全进程只尝试一次。
     * <p>若 init 任务已在后台跑（prewarm 预热过、管道阻塞未完成），本次请求只快速
     * 检查、不阻塞等待——避免 prewarm 超时后每个请求再各等满一次超时。
     */
    private void ensure() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            Future<?> task = initTask;
            if (task == null) {
                initTask = task = initExecutor.submit(this::initGuarded);
                awaitInit(task);
            } else if (task.isDone()) {
                // prewarm 已跑完但还没置位：首次请求取一次结果（含超时/失败分支）
                awaitInit(task);
            } else {
                // 后台仍在初始化（典型：Docker 管道阻塞中）：请求不等待，按空工具面放行；
                // 若后台最终完成，volatile 工具字段填充后后续请求仍能取到
                log.warn("[sandbox] 沙箱仍在后台初始化中，本次请求不等待，沙箱工具面暂时为空");
                initialized = true;
            }
        }
    }

    /** 限时等待 init 任务完成，超时/异常/中断均记日志并放行（工具面为空，不重试） */
    private void awaitInit(Future<?> task) {
        try {
            task.get(initTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true); // 中断底层阻塞的管道/Docker 连接，防守护线程永久占用
            log.warn("[sandbox] 沙箱初始化超过 {}s（Docker 运行中？），本进程放弃等待，"
                    + "沙箱工具面为空、不回退宿主机工具", initTimeoutMs / 1000);
        } catch (ExecutionException e) {
            log.warn("[sandbox] 沙箱初始化失败（Docker 环境？），沙箱工具面为空，不回退宿主机工具: {}",
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[sandbox] 等待沙箱初始化被中断，沙箱工具面为空");
        } finally {
            initialized = true;
        }
    }

    /** 应用就绪后后台预热沙箱：Docker 可用时首请求零等待，不可用时仅在后台失败不影响请求 */
    @EventListener(ApplicationReadyEvent.class)
    public void prewarm() {
        synchronized (lock) {
            if (initTask == null) {
                initTask = initExecutor.submit(this::initGuarded);
            }
        }
    }

    /** init 的守护包装：任何异常（含取消中断）只记日志，绝不上抛拖垮后台线程 */
    private void initGuarded() {
        try {
            init();
        } catch (Throwable t) {
            log.warn("[sandbox] 沙箱初始化异常（Docker 环境？）: {}", t.toString());
        }
    }

    /** 懒初始化 browser 组：与 base 组共用 SandboxService（close 时一起释放），失败只降级本组 */
    private void ensureBrowser() {
        ensure(); // browser 容器复用同一 SandboxService，先确保服务已启动
        if (browserInitialized) {
            return;
        }
        synchronized (browserLock) {
            if (browserInitialized) {
                return;
            }
            try {
                SandboxService svc = service;
                if (svc == null) {
                    log.warn("[sandbox] 沙箱服务不可用，浏览器工具组为空");
                } else {
                    Sandbox browserBox = new BrowserSandbox(svc, SANDBOX_USER, SANDBOX_SESSION);
                    this.browser = List.of(
                            ToolkitInit.BrowserNavigateTool(browserBox),
                            ToolkitInit.BrowserSnapshotTool(browserBox),
                            ToolkitInit.BrowserClickTool(browserBox),
                            ToolkitInit.BrowserTypeTool(browserBox),
                            ToolkitInit.BrowserCloseTool(browserBox));
                    log.info("[sandbox] 浏览器沙箱就绪: 浏览器类={}", browser.size());
                }
            } catch (Throwable t) {
                log.warn("[sandbox] 浏览器沙箱初始化失败（runtime-sandbox-browser 镜像？），"
                        + "浏览器工具组为空，其余沙箱工具不受影响: {}", t.toString());
            }
            browserInitialized = true;
        }
    }

    protected void init() throws Exception {
        SandboxService svc = new SandboxService(ManagerConfig.builder().build());
        svc.start();
        Sandbox sandbox = new BaseSandbox(svc, SANDBOX_USER, SANDBOX_SESSION);

        this.base = List.of(
                ToolkitInit.RunPythonCodeTool(sandbox),
                ToolkitInit.RunShellCommandTool(sandbox));
        this.readOnly = List.of(
                ToolkitInit.ReadFileTool(sandbox),
                ToolkitInit.ReadMultipleFilesTool(sandbox),
                ToolkitInit.ListDirectoryTool(sandbox),
                ToolkitInit.DirectoryTreeTool(sandbox),
                ToolkitInit.SearchFilesTool(sandbox),
                ToolkitInit.GetFileInfoTool(sandbox));
        this.write = List.of(
                ToolkitInit.WriteFileTool(sandbox),
                ToolkitInit.EditFileTool(sandbox),
                ToolkitInit.CreateDirectoryTool(sandbox),
                ToolkitInit.MoveFileTool(sandbox));
        this.service = svc;
        log.info("[sandbox] 容器级沙箱就绪: 执行类={} 只读={} 写入={}",
                base.size(), readOnly.size(), write.size());
    }

    /** 应用退出时释放沙箱服务与全部容器（base + browser），并关闭初始化线程池 */
    @PreDestroy
    public void shutdown() {
        initExecutor.shutdownNow();
        try {
            SandboxService svc = service;
            if (svc != null) {
                svc.close();
                log.info("[sandbox] 沙箱服务已关闭");
            }
        } catch (Exception e) {
            log.warn("[sandbox] 沙箱关闭异常: {}", e.getMessage());
        }
    }
}
