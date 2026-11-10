package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import local.autoclaw.mypycharm.GitCmd;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import local.autoclaw.mypycharm.MyUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 分离仓库操作动作集（对齐 vscode-my-vscode 的 SCM 命令面）：
 * 刷新 / 重新加载绑定 / 切换分支 / 推送 / 拉取 / 同步 / 发布 / 管理远程 /
 * 暂存与放弃（选中或全部）。
 */
public final class MyVcsActions {
    private MyVcsActions() {
    }

    private static MyReposService svc(Project p) {
        return MyReposService.getInstance(p);
    }

    private static boolean enabled(AnActionEvent e) {
        return e.getProject() != null && !e.getProject().isDisposed() && svc(e.getProject()).hasBindings();
    }

    // ---------- 基础设施 ----------

    /** 解析操作目标仓库：选中文件 → 单仓库自动 → 弹窗选择（对齐 resolveRepo）。 */
    static void withRepo(AnActionEvent e, Consumer<MyBinding> action) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        List<MyBinding> bs = svc(project).bindings();
        if (bs.isEmpty()) {
            MyUi.info(project, "My Pycharm：当前没有可用的分离仓库绑定。");
            return;
        }
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null) {
            MyBinding b = svc(project).bindingFor(vf.getPath());
            if (b != null) {
                action.accept(b);
                return;
            }
        }
        if (bs.size() == 1) {
            action.accept(bs.get(0));
            return;
        }
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(bs)
                .setTitle("选择要操作的分离仓库")
                .setRenderer(repoRenderer())
                .setItemChosenCallback(action::accept)
                .createPopup()
                .showInFocusCenter();
    }

    static ColoredListCellRenderer<MyBinding> repoRenderer() {
        return new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends MyBinding> l, MyBinding value,
                                                 int i, boolean s, boolean f) {
                append(value.label(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + value.workTree(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        };
    }

    /** 后台跑 git，结束后通知 + 全量刷新。 */
    static void runGit(Project project, MyBinding b, List<String> args, String verb, long timeoutMs) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：" + verb + " — " + b.label(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                GitCmd.Result r = GitCmd.git(b.gitDir(), args, b.workTree(), timeoutMs);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (r.code == 0) {
                        MyUi.info(project, "My Pycharm：" + b.label() + " " + verb + "完成。");
                    } else {
                        MyUi.error(project, "My Pycharm：" + b.label() + " " + verb + "失败\n" + r.stderr.trim());
                    }
                    svc(project).refreshAll();
                });
            }
        });
    }

    record Remote(String name, String url) {
    }

    record Item(String label, Runnable run) {
        @Override
        public String toString() {
            return label;
        }
    }

    static List<Remote> listRemotes(MyBinding b) {
        GitCmd.Result r = GitCmd.git(b.gitDir(), List.of("remote", "-v"), b.workTree(), 15000);
        List<Remote> out = new ArrayList<>();
        if (r.code != 0) {
            return out;
        }
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String line : r.stdout.split("\r?\n")) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("^(\\S+)\\t(\\S+)\\s+\\(fetch\\)$").matcher(line.trim());
            if (m.matches()) {
                map.put(m.group(1), m.group(2));
            }
        }
        map.forEach((n, u) -> out.add(new Remote(n, u)));
        return out;
    }

    // ---------- 刷新 / 重新加载 ----------

    public static class RefreshAction extends AnAction {
        public RefreshAction() {
            super("刷新所有仓库", "My Pycharm：刷新所有仓库", com.intellij.icons.AllIcons.Actions.Refresh);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            svc(e.getProject()).refreshAll();
        }
    }

    public static class ReloadBindingsAction extends AnAction {
        public ReloadBindingsAction() {
            super("重新加载绑定", "My Pycharm：重新发现 *-gl.sh 与手动绑定", com.intellij.icons.AllIcons.Actions.Restart);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(e.getProject() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                svc(project).reload();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        svc(project).applyDirectoryMappings();
                    }
                });
            });
        }
    }

    // ---------- 分支 ----------

    public static class BranchAction extends AnAction {
        public BranchAction() {
            super("切换分支", "My Pycharm：切换分支", com.intellij.icons.AllIcons.Vcs.Branch);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> {
                Project project = e.getProject();
                ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：读取分支", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        GitCmd.Result cur = GitCmd.git(b.gitDir(), List.of("symbolic-ref", "-q", "--short", "HEAD"), b.workTree(), 15000);
                        GitCmd.Result r = GitCmd.git(b.gitDir(), List.of("branch", "--format=%(refname:short)"), b.workTree(), 15000);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (project.isDisposed()) {
                                return;
                            }
                            List<String> names = new ArrayList<>();
                            for (String s : r.stdout.split("\r?\n")) {
                                String t = s.trim();
                                if (!t.isEmpty()) {
                                    names.add(t);
                                }
                            }
                            if (names.isEmpty()) {
                                MyUi.info(project, "My Pycharm：" + b.label() + " 没有本地分支。");
                                return;
                            }
                            String curBranch = cur.stdout.trim();
                            JBPopupFactory.getInstance()
                                    .createPopupChooserBuilder(names)
                                    .setTitle("切换分支 — " + b.label())
                                    .setRenderer(new ColoredListCellRenderer<String>() {
                                        @Override
                                        protected void customizeCellRenderer(@NotNull JList<? extends String> l,
                                                                             String value, int i, boolean s, boolean f) {
                                            append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                                            if (value.equals(curBranch)) {
                                                append("（当前）", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                                            }
                                        }
                                    })
                                    .setItemChosenCallback(name -> {
                                        if (!name.equals(curBranch)) {
                                            runGit(project, b, List.of("checkout", name), "切换分支 " + name, 120000);
                                        }
                                    })
                                    .createPopup()
                                    .showInFocusCenter();
                        });
                    }
                });
            });
        }
    }

    // ---------- 同步类 ----------

    public static class PushAction extends AnAction {
        public PushAction() {
            super("推送", "My Pycharm：推送", com.intellij.icons.AllIcons.Vcs.Push);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> runGit(e.getProject(), b, List.of("push"), "推送", 120000));
        }
    }

    public static class PullAction extends AnAction {
        public PullAction() {
            super("拉取", "My Pycharm：拉取", com.intellij.icons.AllIcons.Vcs.Fetch);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> runGit(e.getProject(), b, List.of("pull"), "拉取", 120000));
        }
    }

    public static class SyncAction extends AnAction {
        public SyncAction() {
            super("同步更改（拉取+推送）", "My Pycharm：同步更改", com.intellij.icons.AllIcons.Actions.Refresh);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> runGit(e.getProject(), b, List.of("pull"), "拉取", 120000));
            withRepo(e, b -> runGit(e.getProject(), b, List.of("push"), "推送", 120000));
        }
    }

    public static class PublishAction extends AnAction {
        public PublishAction() {
            super("发布分支到远程", "My Pycharm：发布分支到远程（push -u）", com.intellij.icons.AllIcons.Actions.Upload);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> {
                Project project = e.getProject();
                ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：读取远程", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        List<Remote> remotes = listRemotes(b);
                        GitCmd.Result br = GitCmd.git(b.gitDir(), List.of("symbolic-ref", "-q", "--short", "HEAD"), b.workTree(), 15000);
                        String branch = br.stdout.trim();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (project.isDisposed()) {
                                return;
                            }
                            if (remotes.isEmpty()) {
                                int ok = Messages.showYesNoDialog(project,
                                        "My Pycharm：" + b.label() + " 还没有远程仓库，先添加远程？",
                                        "发布分支", "添加远程", "取消", Messages.getQuestionIcon());
                                if (ok == Messages.YES) {
                                    manageRemotes(project, b);
                                }
                                return;
                            }
                            if (remotes.size() == 1) {
                                runGit(project, b, List.of("push", "-u", remotes.get(0).name(), branch),
                                        "发布分支 " + branch + " 到 " + remotes.get(0).name(), 180000);
                                return;
                            }
                            JBPopupFactory.getInstance()
                                    .createPopupChooserBuilder(remotes)
                                    .setTitle("发布分支 " + branch + " 到哪个远程？")
                                    .setRenderer(new ColoredListCellRenderer<Remote>() {
                                        @Override
                                        protected void customizeCellRenderer(@NotNull JList<? extends Remote> l,
                                                                             Remote value, int i, boolean s, boolean f) {
                                            append(value.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                                            append("  " + value.url(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                                        }
                                    })
                                    .setItemChosenCallback(rm ->
                                            runGit(project, b, List.of("push", "-u", rm.name(), branch),
                                                    "发布分支 " + branch + " 到 " + rm.name(), 180000))
                                    .createPopup()
                                    .showInFocusCenter();
                        });
                    }
                });
            });
        }
    }

    // ---------- 远程管理 ----------

    public static class RemotesAction extends AnAction {
        public RemotesAction() {
            super("管理远程", "My Pycharm：管理远程仓库", com.intellij.icons.AllIcons.Actions.Properties);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> manageRemotes(e.getProject(), b));
        }
    }

    static void manageRemotes(Project project, MyBinding b) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：读取远程", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<Remote> remotes = listRemotes(b);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    List<Item> items = new ArrayList<>();
                    items.add(new Item("＋ 添加远程…", () -> addRemoteFlow(project, b)));
                    for (Remote r : remotes) {
                        items.add(new Item(r.name() + " — " + r.url(), () -> remoteActions(project, b, r)));
                    }
                    JBPopupFactory.getInstance()
                            .createPopupChooserBuilder(items)
                            .setTitle(b.label() + "：管理远程（Esc 退出）")
                            .setItemChosenCallback(Item::run)
                            .createPopup()
                            .showInFocusCenter();
                });
            }
        });
    }

    private static void remoteActions(Project project, MyBinding b, Remote r) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("复制 URL", () -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(r.url()));
            MyUi.info(project, "My Pycharm：已复制：" + r.url());
        }));
        items.add(new Item("修改 URL…", () -> {
            String next = Messages.showInputDialog(project, "新的 URL（" + r.name() + "）",
                    "My Pycharm", Messages.getInformationIcon(), r.url(), null);
            if (next != null && !next.isBlank() && !next.equals(r.url())) {
                runGit(project, b, List.of("remote", "set-url", r.name(), next.trim()),
                        "更新远程 " + r.name(), 15000);
            }
        }));
        items.add(new Item("删除远程…", () -> {
            int ok = Messages.showYesNoDialog(project,
                    "删除远程 " + r.name() + "？（" + r.url() + "）",
                    "My Pycharm", "删除", "取消", Messages.getWarningIcon());
            if (ok == Messages.YES) {
                runGit(project, b, List.of("remote", "remove", r.name()), "删除远程 " + r.name(), 15000);
            }
        }));
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(items)
                .setTitle("远程 " + r.name())
                .setItemChosenCallback(Item::run)
                .createPopup()
                .showInFocusCenter();
    }

    private static void addRemoteFlow(Project project, MyBinding b) {
        String name = Messages.showInputDialog(project, "远程名称（如 origin）",
                "My Pycharm：添加远程", Messages.getInformationIcon());
        if (name == null || name.isBlank()) {
            return;
        }
        String url = Messages.showInputDialog(project, "远程 URL（" + name.trim() + "）",
                "My Pycharm：添加远程", Messages.getInformationIcon());
        if (url == null || url.isBlank()) {
            return;
        }
        runGit(project, b, List.of("remote", "add", name.trim(), url.trim()),
                "添加远程 " + name.trim(), 15000);
    }

    // ---------- 暂存 / 放弃（全部） ----------

    public static class StageAllAction extends AnAction {
        public StageAllAction() {
            super("暂存全部", "My Pycharm：git add -A", com.intellij.icons.AllIcons.General.Add);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> runGit(e.getProject(), b, List.of("add", "-A"), "暂存全部", 60000));
        }
    }

    public static class UnstageAllAction extends AnAction {
        public UnstageAllAction() {
            super("取消全部暂存", "My Pycharm：reset / rm --cached", com.intellij.icons.AllIcons.General.Delete);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withRepo(e, b -> unstage(e.getProject(), b, List.of(".")));
        }
    }

    public static class DiscardAllAction extends AnAction {
        public DiscardAllAction() {
            super("放弃全部更改", "My Pycharm：checkout + clean", com.intellij.icons.AllIcons.Actions.Rollback);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            withRepo(e, b -> {
                int ok = Messages.showYesNoDialog(project,
                        "放弃 " + b.label() + " 工作树的全部更改（不可恢复）？\n未跟踪文件将被直接删除。",
                        "放弃全部更改", "放弃更改", "取消", Messages.getWarningIcon());
                if (ok != Messages.YES) {
                    return;
                }
                GitCmd.git(b.gitDir(), List.of("clean", "-f", "-d"), b.workTree(), 60000);
                runGit(project, b, List.of("checkout", "--", "."), "放弃全部更改", 60000);
            });
        }
    }

    static void unstage(Project project, MyBinding b, List<String> rels) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：取消暂存", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                boolean hasHead = GitCmd.git(b.gitDir(), List.of("rev-parse", "-q", "--verify", "HEAD"), b.workTree(), 15000).code == 0;
                List<String> args = new ArrayList<>();
                if (hasHead) {
                    args.add("reset");
                    args.add("-q");
                    args.add("HEAD");
                    args.add("--");
                } else {
                    args.add("rm");
                    args.add("--cached");
                    args.add("-r");
                    args.add("-q");
                    args.add("--");
                }
                args.addAll(rels);
                GitCmd.Result r = GitCmd.git(b.gitDir(), args, b.workTree(), 60000);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        if (r.code != 0) {
                            MyUi.error(project, "My Pycharm：" + b.label() + " 取消暂存失败\n" + r.stderr.trim());
                        }
                        svc(project).refreshAll();
                    }
                });
            }
        });
    }

    // ---------- 暂存 / 放弃（选中变更） ----------

    static List<String[]> collectSelected(AnActionEvent e) {
        // 返回 {relPath, afterNull} — afterNull=1 表示已删除（需要 add -A）
        List<String[]> out = new ArrayList<>();
        Change[] changes = e.getData(VcsDataKeys.SELECTED_CHANGES);
        if (changes == null) {
            return out;
        }
        MyReposService s = svc(e.getProject());
        for (Change c : changes) {
            MyBinding b = MyCheckinEnvironment.bindingOf(s, c);
            if (b == null) {
                continue;
            }
            String abs = c.getAfterRevision() != null ? c.getAfterRevision().getFile().getPath()
                    : c.getBeforeRevision() != null ? c.getBeforeRevision().getFile().getPath() : null;
            if (abs == null) {
                continue;
            }
            String rel = b.toRel(abs);
            if (rel != null) {
                out.add(new String[]{rel, c.getAfterRevision() == null ? "1" : "0"});
            }
        }
        return out;
    }

    public static class StageSelectedAction extends AnAction {
        public StageSelectedAction() {
            super("暂存", "My Pycharm：暂存选中变更", com.intellij.icons.AllIcons.General.Add);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e) && !collectSelected(e).isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            List<String[]> sel = collectSelected(e);
            if (sel.isEmpty()) {
                return;
            }
            MyReposService s = svc(project);
            for (String[] item : sel) {
                MyBinding b = s.bindingFor(pathOf(project, item[0]));
                if (b == null) {
                    continue;
                }
                List<String> args = "1".equals(item[1])
                        ? List.of("add", "-A", "--", item[0])
                        : List.of("add", "--", item[0]);
                runGit(project, b, args, "暂存 " + item[0], 60000);
            }
        }
    }

    public static class UnstageSelectedAction extends AnAction {
        public UnstageSelectedAction() {
            super("取消暂存", "My Pycharm：取消暂存选中变更", com.intellij.icons.AllIcons.General.Delete);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e) && !collectSelected(e).isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            List<String[]> sel = collectSelected(e);
            if (sel.isEmpty()) {
                return;
            }
            MyReposService s = svc(project);
            // 同一仓库的选中项一次处理
            MyBinding first = null;
            for (String[] item : sel) {
                MyBinding b = s.bindingFor(pathOf(project, item[0]));
                if (b == null) {
                    continue;
                }
                first = b;
                break;
            }
            if (first == null) {
                return;
            }
            List<String> rels = new ArrayList<>();
            for (String[] item : sel) {
                rels.add(item[0]);
            }
            unstage(project, first, rels);
        }
    }

    public static class DiscardSelectedAction extends AnAction {
        public DiscardSelectedAction() {
            super("放弃更改", "My Pycharm：放弃选中更改（不可恢复）", com.intellij.icons.AllIcons.Actions.Rollback);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(enabled(e) && !collectSelected(e).isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            List<String[]> sel = collectSelected(e);
            if (sel.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("放弃以下更改（不可恢复）？\n");
            for (int i = 0; i < sel.size() && i < 8; i++) {
                sb.append(sel.get(i)[0]).append('\n');
            }
            if (sel.size() > 8) {
                sb.append("… 共 ").append(sel.size()).append(" 个文件\n");
            }
            sb.append("\n未跟踪文件将被直接删除。");
            int ok = Messages.showYesNoDialog(project, sb.toString(), "放弃更改",
                    "放弃更改", "取消", Messages.getWarningIcon());
            if (ok != Messages.YES) {
                return;
            }
            MyReposService s = svc(project);
            for (String[] item : sel) {
                MyBinding b = s.bindingFor(pathOf(project, item[0]));
                if (b == null) {
                    continue;
                }
                boolean inIndex = GitCmd.existsInRev(b.gitDir(), ":" + item[0]);
                boolean inHead = GitCmd.existsInRev(b.gitDir(), "HEAD:" + item[0]);
                GitCmd.git(b.gitDir(), (inIndex || inHead)
                                ? List.of("checkout", "--", item[0])
                                : List.of("clean", "-f", "-d", "--", item[0]),
                        b.workTree(), 60000);
            }
            s.refreshAll();
        }
    }

    /** rel 路径反查绝对路径：借第一个含该 rel 的绑定拼出。 */
    @Nullable
    private static String pathOf(Project project, String rel) {
        for (MyBinding b : svc(project).bindings()) {
            return b.joinFs(rel);
        }
        return rel;
    }
}
