package com.dark.javaHarness.tool;

import com.alibaba.cloud.ai.sandbox.ToolkitInit;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.box.Sandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 容器级沙箱工具提供者（spring-ai-alibaba-sandbox / agentscope-runtime）。
 *
 * <p>职责：把 agentscope 沙箱里的 ToolCallback（Python 执行 / Shell / 文件读写检索）
 * 按安全类别暴露给 {@link ToolAssignments}，替代已退役的宿主机工具
 * （FileTools/SearchTools/ShellTools——模型生成的代码与命令只在容器内执行，宿主机零暴露）。
 *
 * <p>初始化策略（架构决策：沙箱是硬依赖、不做宿主机降级）：
 * 懒初始化（首次取用才拉起 Docker 容器），失败时记录 warn 并返回空工具面、
 * 进程内不再重试——即无 Docker 环境下沙箱类能力整体不可用，但应用其余功能不受影响；
 * 绝不回退到宿主机执行（文件/Shell 工具已删除，无退路可走）。
 */
@Component
public class SandboxToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolProvider.class);

    /** 沙箱内固定的用户/会话标识（容器命名与日志归因） */
    private static final String SANDBOX_USER = "javaHarness";
    private static final String SANDBOX_SESSION = "harness";

    private final Object lock = new Object();
    private volatile boolean initialized;

    private volatile List<ToolCallback> base = List.of();
    private volatile List<ToolCallback> readOnly = List.of();
    private volatile List<ToolCallback> write = List.of();
    private volatile SandboxService service;

    /** 执行类：Python 代码执行 + Shell 命令（容器内） */
    public List<ToolCallback> baseTools() {
        ensure();
        return base;
    }

    /** 只读文件类：读文件/批量读/列目录/目录树/搜索/文件信息（容器内文件系统） */
    public List<ToolCallback> readOnlyFileTools() {
        ensure();
        return readOnly;
    }

    /** 写入类：写文件/编辑替换/建目录/移动（容器内文件系统） */
    public List<ToolCallback> writeTools() {
        ensure();
        return write;
    }

    /** 懒初始化：双检锁保证只尝试一次；任何失败都降级为空工具面（宿主机零暴露，不重试） */
    private void ensure() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            try {
                init();
            } catch (Throwable t) {
                log.warn("[sandbox] 沙箱初始化失败（Docker 环境？），沙箱工具面为空，不回退宿主机工具: {}",
                        t.toString());
            }
            initialized = true;
        }
    }

    private void init() throws Exception {
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

    /** 应用退出时释放沙箱服务与容器 */
    @PreDestroy
    public void shutdown() {
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
