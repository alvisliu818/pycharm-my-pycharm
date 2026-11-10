package local.autoclaw.mypycharm;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 项目级绑定仓库服务：
 * - 发现绑定（settings 手动 + 各根目录 *-gl.sh 自动），自管仓库让给内置 Git 集成；
 * - 维护「激活绑定」列表，供 VCS / 补全 / 图表各处按路径解析；
 * - 负责目录映射与全量刷新。
 */
@Service(Service.Level.PROJECT)
public final class MyReposService implements Disposable {
    private static final Logger LOG = Logger.getInstance(MyReposService.class);

    /** 全量刷新事件：动作 / 提交 / 回滚后发布，图表面板订阅。 */
    public static final Topic<Runnable> REFRESH_TOPIC =
            new Topic<>("MY_PYCHARM_REFRESH", Runnable.class);

    public static final String VCS_NAME = "my-pycharm";

    private final Project project;
    private volatile List<MyBinding> active = List.of();

    public MyReposService(Project project) {
        this.project = project;
    }

    public static MyReposService getInstance(Project project) {
        return project.getService(MyReposService.class);
    }

    public List<MyBinding> bindings() {
        return active;
    }

    /** 当前激活绑定数 > 0。 */
    public boolean hasBindings() {
        return !active.isEmpty();
    }

    /** 按绝对路径解析所属绑定（最长前缀优先，大小写不敏感）。 */
    public MyBinding bindingFor(String fsPath) {
        MyBinding best = null;
        int bestLen = -1;
        for (MyBinding b : active) {
            String rel = b.toRel(fsPath);
            if (rel != null && b.workTree().length() > bestLen) {
                best = b;
                bestLen = b.workTree().length();
            }
        }
        return best;
    }

    public MessageBusConnection connectRefresh(Disposable parent, Runnable listener) {
        MessageBusConnection conn = project.getMessageBus().connect(parent);
        conn.subscribe(REFRESH_TOPIC, listener);
        return conn;
    }

    public void publishRefresh() {
        project.getMessageBus().syncPublisher(REFRESH_TOPIC).run();
    }

    // ---------- 发现与映射 ----------

    /**
     * 重新发现绑定。含 git 子进程调用（自管检测），必须在后台线程执行。
     * 对齐 extension.ts 的 reload()。
     */
    public void reload() {
        List<MyBinding> discovered = new ArrayList<>();
        Map<String, MyBinding> byGitDir = new LinkedHashMap<>();
        MySettings st = MySettings.getInstance(project);

        // 1) 手动绑定
        for (MySettings.BindingDef d : st.getState().bindings) {
            String wt = Pure.normalizePath(d.workTree);
            String gd = Pure.normalizePath(d.gitDir);
            if (wt == null || gd == null || wt.isEmpty() || gd.isEmpty()) {
                continue;
            }
            addBinding(byGitDir, discovered, new MyBinding(wt, gd, d.label == null || d.label.isBlank() ? MyBinding.basename(wt) : d.label, d.force));
        }

        // 2) 自动发现：各根目录下 *-gl.sh
        if (st.getState().autoDiscover) {
            for (VirtualFile root : discoverRoots()) {
                File dir = new File(root.getPath());
                File[] files = dir.listFiles((d, name) -> Pure.isWrapperScriptName(name));
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    try {
                        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        Pure.WrapperMatch m = Pure.parseWrapperScript(text);
                        if (m.gitDir != null && m.workTree != null) {
                            addBinding(byGitDir, discovered, new MyBinding(m.workTree, m.gitDir, MyBinding.basename(m.workTree), false));
                        }
                    } catch (Exception e) {
                        LOG.info("[my-pycharm] 读取包装脚本失败: " + f + " — " + e.getMessage());
                    }
                }
            }
        }

        // 3) 自管检测：workTree 里 rev-parse --show-toplevel 是自身时让给内置 git
        List<MyBinding> kept = new ArrayList<>();
        int skipped = 0;
        for (MyBinding b : discovered) {
            if (!b.force() && isSelfManaged(b.workTree())) {
                LOG.info("[my-pycharm] [绑定] 跳过 " + b.label() + "（目录内已有自己的 .git，交给内置 Git 集成）");
                skipped++;
                continue;
            }
            kept.add(b);
            LOG.info("[my-pycharm] [绑定] " + b.label() + (b.force() ? "（force）" : "") + ": work-tree=" + b.workTree() + " git-dir=" + b.gitDir());
        }
        active = List.copyOf(kept);
        LOG.info("[my-pycharm] [绑定] 共 " + kept.size() + " 个分离仓库"
                + (discovered.size() != kept.size() ? "（另跳过 " + skipped + " 个）" : ""));
    }

    private static void addBinding(Map<String, MyBinding> byGitDir, List<MyBinding> out, MyBinding b) {
        String key = Pure.lower(Pure.normalizePath(b.gitDir()));
        if (byGitDir.containsKey(key)) {
            return;
        }
        byGitDir.put(key, b);
        out.add(b);
    }

    /** 项目基目录 + 全部内容根，去重。 */
    private List<VirtualFile> discoverRoots() {
        Set<String> seen = new HashSet<>();
        List<VirtualFile> roots = new ArrayList<>();
        VirtualFile base = project.getBaseDir();
        if (base != null && seen.add(base.getPath())) {
            roots.add(base);
        }
        try {
            for (VirtualFile v : com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getContentRoots()) {
                if (v != null && seen.add(v.getPath())) {
                    roots.add(v);
                }
            }
        } catch (Exception ignore) {
        }
        return roots;
    }

    /** workTree 是否「自己能被发现的仓库」（rev-parse --show-toplevel == 自身）。 */
    public boolean isSelfManaged(String dir) {
        try {
            GitCmd.Result r = GitCmd.gitPlain(dir, List.of("rev-parse", "--show-toplevel"), 15000);
            if (r.code != 0 || r.stdout.isBlank()) {
                return false;
            }
            String top = Pure.normalizePath(r.stdout.trim());
            String self = Pure.normalizePath(dir);
            return top != null && self != null && Pure.lower(top).equals(Pure.lower(self));
        } catch (Exception e) {
            return false; // git 不可用时仍然接管（对齐 extension.ts）
        }
    }

    /** 应用目录映射（必须在 EDT 调用）。 */
    public void applyDirectoryMappings() {
        if (project.isDefault()) {
            return;
        }
        ProjectLevelVcsManager plm = ProjectLevelVcsManager.getInstance(project);
        List<VcsDirectoryMapping> maps = new ArrayList<>(plm.getDirectoryMappings());
        maps.removeIf(m -> VCS_NAME.equals(m.getVcs()));
        if (!active.isEmpty()) {
            VirtualFile base = project.getBaseDir();
            boolean mapped = false;
            if (base != null && !isSelfManaged(base.getPath())) {
                // 项目根本身不是自管仓库 → 整个根交给本插件
                maps.add(new VcsDirectoryMapping(base.getPath(), VCS_NAME));
                mapped = true;
            }
            if (!mapped) {
                // 项目根是自管仓库（内置 git 接管），分离仓库按各自工作树目录接管
                for (MyBinding b : active) {
                    maps.add(new VcsDirectoryMapping(b.workTree(), VCS_NAME));
                }
            }
        }
        plm.setDirectoryMappings(maps);
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        publishRefresh();
    }

    /** 全量刷新：标记所有文件脏 + 通知图表。 */
    public void refreshAll() {
        if (project.isDisposed()) {
            return;
        }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        publishRefresh();
    }

    /** 启动入口调用：后台 reload + EDT 应用映射。 */
    public void bootstrap() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            reload();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    applyDirectoryMappings();
                }
            });
        });
    }

    @Override
    public void dispose() {
        active = List.of();
    }
}
