package local.autoclaw.mypycharm.graph;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.contents.DiffContent;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import local.autoclaw.mypycharm.GitCmd;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import local.autoclaw.mypycharm.Pure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 提交图表面板：跟随当前仓库，`git log --graph -n 300` 渲染（对齐 vscode-my-vscode 的 graph.ts）。
 * 双击提交 → 弹出变更文件列表 → 选择文件查看差异（父提交 ↔ 该提交）。
 */
public final class MyGraphPanel extends SimpleToolWindowPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(MyGraphPanel.class);

    private static final String LOG_FORMAT =
            "--pretty=format:%x01%H%x1f%P%x1f%h%x1f%an%x1f%at%x1f%s";

    /** 列表行：commit != null 为提交行，否则为提示行。 */
    record Row(Pure.GraphCommit commit, String message) {
    }

    private final Project project;
    private volatile MyBinding current;
    private final JBList<Row> list = new JBList<>();
    private final DefaultListModel<Row> model = new DefaultListModel<>();

    public MyGraphPanel(Project project, ToolWindow toolWindow) {
        super(true, true);
        this.project = project;

        list.setModel(model);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    Row row = list.getSelectedValue();
                    if (row != null && row.commit() != null) {
                        openCommit(row.commit());
                    }
                }
            }
        });

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ChooseRepoAction());
        group.add(new RefreshAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("MyPycharmGraphToolbar", group, true);
        toolbar.setTargetComponent(list);
        setToolbar(toolbar.getComponent());
        setContent(new JBScrollPane(list));

        // 跟随编辑器文件所属仓库
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        follow(file);
                    }

                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        if (event.getNewFile() != null) {
                            follow(event.getNewFile());
                        }
                    }
                });
        // 动作 / 提交 / 回滚后的全量刷新
        MyReposService.getInstance(project).connectRefresh(this, this::refresh);
        refresh();
    }

    private void follow(VirtualFile file) {
        MyBinding b = MyReposService.getInstance(project).bindingFor(file.getPath());
        if (b != null && b != current) {
            current = b;
            refresh();
        }
    }

    public void refresh() {
        if (project.isDisposed()) {
            return;
        }
        MyBinding b = current;
        if (b == null) {
            setMessageOnEdt("没有仓库 — 打开绑定仓库的文件，或点击工具栏选择仓库");
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：读取提交图表", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                GitCmd.Result r = GitCmd.git(b.gitDir(),
                        List.of("log", "--graph", "--date-order", "-n", "300", LOG_FORMAT),
                        b.workTree(), 60000);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (r.code != 0) {
                        LOG.info("[my-pycharm] [" + b.label() + "] git log 失败: " + r.stderr.trim());
                        setMessageOnEdt("无法读取提交历史：" + r.firstStderrLine());
                        return;
                    }
                    List<Pure.GraphCommit> commits = Pure.parseGraphLog(r.stdout);
                    model.clear();
                    if (commits.isEmpty()) {
                        model.addElement(new Row(null, "空仓库，还没有提交。"));
                        return;
                    }
                    for (Pure.GraphCommit c : commits) {
                        model.addElement(new Row(c, null));
                    }
                });
            }
        });
    }

    private void setMessageOnEdt(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                model.clear();
                model.addElement(new Row(null, msg));
            }
        });
    }

    // —— 点击提交 → 变更文件 → 差异 ——

    private void openCommit(Pure.GraphCommit c) {
        MyBinding b = current;
        if (b == null) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：读取提交 " + c.shortId, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                GitCmd.Result r = GitCmd.git(b.gitDir(),
                        List.of("diff-tree", "--no-commit-id", "--name-status", "-r", "-M", "-z", c.id),
                        b.workTree(), 60000);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (r.code != 0) {
                        MyUi_error("读取提交 " + c.shortId + " 失败\n" + r.stderr.trim());
                        return;
                    }
                    List<String[]> changes = parseDiffTree(r.stdout); // {st, path, orig?}
                    if (changes.isEmpty()) {
                        MyUi_info("提交 " + c.shortId + "（" + c.subject + "）没有文件变更（可能是合并提交）。");
                        return;
                    }
                    JBPopupFactory.getInstance()
                            .createPopupChooserBuilder((List<String[]>) changes)
                            .setTitle(c.shortId + " " + c.subject + " — 选择文件查看差异")
                            .setItemChosenCallback(ch -> showCommitDiff(b, c, ch))
                            .setRenderer(new ColoredListCellRenderer<String[]>() {
                                @Override
                                protected void customizeCellRenderer(@NotNull JList<? extends String[]> l,
                                                                     @NotNull String[] value, int i, boolean s, boolean f) {
                                    if (value[2] != null && !value[2].isEmpty()) {
                                        append(value[2] + " → ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                                    }
                                    append(value[1], SimpleTextAttributes.REGULAR_ATTRIBUTES);
                                    append("  " + value[0], SimpleTextAttributes.GRAYED_ATTRIBUTES);
                                }
                            })
                            .createPopup()
                            .showInFocusCenter();
                });
            }
        });
    }

    private void showCommitDiff(MyBinding b, Pure.GraphCommit c, String[] ch) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "My Pycharm：加载差异", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String st = ch[0];
                String path = ch[1];
                String orig = ch[2] != null && !ch[2].isEmpty() ? ch[2] : path;
                String parent = c.parents.isEmpty() ? null : c.parents.get(0);
                String leftText;
                String rightText;
                String title;
                String leftTitle;
                String rightTitle;
                if ("A".equals(st)) {
                    leftText = "";
                    rightText = show(c.id + ":" + path);
                    leftTitle = "（空）";
                    rightTitle = path + " @ " + c.shortId;
                    title = path + "（新增 @ " + c.shortId + "）";
                } else if ("D".equals(st)) {
                    leftText = parent != null ? show(parent + ":" + path) : "";
                    rightText = "";
                    leftTitle = path + " @ " + (parent != null ? c.shortId + "~1" : "（空）");
                    rightTitle = "（空）";
                    title = path + "（删除 @ " + c.shortId + "）";
                } else if (ch[2] != null && !ch[2].isEmpty()) {
                    leftText = parent != null ? show(parent + ":" + orig) : "";
                    rightText = show(c.id + ":" + path);
                    leftTitle = orig + " @ " + (parent != null ? c.shortId + "~1" : "（空）");
                    rightTitle = path + " @ " + c.shortId;
                    title = orig + " → " + path + "（" + st + " @ " + c.shortId + "）";
                } else {
                    leftText = parent != null ? show(parent + ":" + path) : "";
                    rightText = show(c.id + ":" + path);
                    leftTitle = path + " @ " + (parent != null ? c.shortId + "~1" : "（空）");
                    rightTitle = path + " @ " + c.shortId;
                    title = path + "（" + c.shortId + "）";
                }
                String lt = leftText;
                String rt = rightText;
                String t = title;
                String lTitle = leftTitle;
                String rTitle = rightTitle;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    DiffContentFactory f = DiffContentFactory.getInstance();
                    DiffContent left = f.create(project, lt);
                    DiffContent right = f.create(project, rt);
                    DiffManager.getInstance().showDiff(project,
                            new SimpleDiffRequest(t, left, right, lTitle, rTitle));
                });
            }

            private String show(String spec) {
                GitCmd.Result r = GitCmd.git(b.gitDir(), List.of("show", spec), null, 30000);
                return r.code == 0 ? r.stdout : "";
            }
        });
    }

    private void MyUi_error(String msg) {
        local.autoclaw.mypycharm.MyUi.error(project, "My Pycharm：" + msg);
    }

    private void MyUi_info(String msg) {
        local.autoclaw.mypycharm.MyUi.info(project, "My Pycharm：" + msg);
    }

    /** 解析 diff-tree -z：状态 \0 路径 \0（重命名/复制时多一个原路径 \0）。 */
    static List<String[]> parseDiffTree(String stdout) {
        List<String[]> out = new ArrayList<>();
        String[] tok = stdout.split("\0", -1);
        int i = 0;
        while (i < tok.length - 1) {
            String st = tok[i++];
            if (st == null || st.isEmpty()) {
                break;
            }
            char code = st.charAt(0);
            if (code == 'R' || code == 'C') {
                String orig = i < tok.length ? tok[i++] : "";
                String p = i < tok.length ? tok[i++] : "";
                if (!p.isEmpty()) {
                    out.add(new String[]{String.valueOf(code), p, orig});
                }
            } else {
                String p = i < tok.length ? tok[i++] : "";
                if (!p.isEmpty()) {
                    out.add(new String[]{String.valueOf(code), p, null});
                }
            }
        }
        return out;
    }

    // —— 工具栏动作 ——

    private final class ChooseRepoAction extends AnAction {
        ChooseRepoAction() {
            super("选择仓库", "提交图表：选择仓库", AllIcons.Vcs.Branch);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(project != null && !project.isDisposed());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            List<MyBinding> bindings = MyReposService.getInstance(project).bindings();
            if (bindings.isEmpty()) {
                MyUi_info("当前没有可用的分离仓库绑定。");
                return;
            }
            JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(bindings)
                    .setTitle("提交图表：选择仓库")
                    .setRenderer(new ColoredListCellRenderer<MyBinding>() {
                        @Override
                        protected void customizeCellRenderer(@NotNull JList<? extends MyBinding> l,
                                                             MyBinding value, int i, boolean s, boolean f) {
                            append(value.label(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                            append("  " + value.workTree(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        }
                    })
                    .setItemChosenCallback(b -> {
                        current = b;
                        refresh();
                    })
                    .createPopup()
                    .showInFocusCenter();
        }
    }

    private final class RefreshAction extends AnAction {
        RefreshAction() {
            super("刷新所有仓库", "刷新所有仓库与提交图表", AllIcons.Actions.Refresh);
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(project != null && !project.isDisposed());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            MyReposService.getInstance(project).refreshAll();
        }
    }

    private static final class Renderer extends ColoredListCellRenderer<Row> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Row> l,
                                             @Nullable Row value, int index, boolean selected, boolean focused) {
            if (value == null) {
                return;
            }
            if (value.message() != null) {
                append(value.message(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            Pure.GraphCommit c = value.commit();
            String g = c.graph.trim();
            if (g.length() > 20) {
                g = g.substring(0, 20) + "…";
            }
            setIcon(AllIcons.Vcs.CommitNode);
            if (!g.isEmpty()) {
                append(g + " ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            append(c.subject, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("  " + c.shortId + " · " + Pure.relTime(c.time) + " · " + c.author,
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }

    @Override
    public void dispose() {
        // messageBus 连接由 Disposable 父级统一释放
    }
}
