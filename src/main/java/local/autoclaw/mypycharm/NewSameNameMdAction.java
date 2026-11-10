package local.autoclaw.mypycharm;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.JSplitPane;
import java.io.IOException;

/**
 * 右键「新建同名 MD 文件」（对齐 myVscode.newSameNameMd）：
 * 在同目录创建 <base>.md（已存在则直接打开），然后打开编辑；
 * 设置「新建同名 MD 文件后在下方分栏打开」开启时，若当前有编辑器，则在下方分栏打开。
 */
public final class NewSameNameMdAction extends AnAction {
    public NewSameNameMdAction() {
        super("新建同名 MD 文件");
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile f = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean ok = f != null && !f.isDirectory() && !"md".equalsIgnoreCase(f.getExtension());
        e.getPresentation().setEnabledAndVisible(ok);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        VirtualFile f = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Project project = e.getProject();
        if (f == null || project == null) {
            return;
        }
        String name = f.getName();
        if ("md".equalsIgnoreCase(f.getExtension())) {
            MyUi.info(project, "My Pycharm：" + name + " 本身就是 MD 文件。");
            return;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isBlank()) {
            MyUi.info(project, "My Pycharm：无法从文件名 " + name + " 生成 MD 文件名。");
            return;
        }
        String mdName = base + ".md";
        VirtualFile dir = f.getParent();
        if (dir == null) {
            MyUi.error(project, "My Pycharm：无法定位目录。");
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                if (dir.findChild(mdName) == null) {
                    dir.createChildData(this, mdName);
                }
            } catch (IOException ex) {
                MyUi.error(project, "My Pycharm：创建 " + mdName + " 失败：" + ex.getMessage());
            }
        });
        VirtualFile md = dir.findChild(mdName);
        if (md == null) {
            return;
        }
        if (MySettings.getInstance(project).getState().openMdInSplitDown) {
            // 下方分栏打开：需要当前有编辑器可拆；没有则回退普通打开
            FileEditorManagerEx fem = FileEditorManagerEx.getInstanceEx(project);
            if (FileEditorManager.getInstance(project).getSelectedTextEditor() != null
                    && fem.getCurrentWindow() != null) {
                // 成对语义：当前栏下方有分栏 → 在已有分栏打开；
                // 当前栏本身就是下方那栏（上次分栏后焦点在 md 栏）→ 就地打开，不再新拆；
                // 都不是 → 新拆下方一栏（VERTICAL_SPLIT=上下、fileIsSecondaryComponent=true=新窗格在下方）
                EditorWindow cur = fem.getCurrentWindow();
                java.util.Map<EditorWindow.RelativePosition, EditorWindow> adj = cur.getAdjacentEditors();
                EditorWindow below = adj.get(EditorWindow.RelativePosition.DOWN);
                if (below != null) {
                    fem.openFileWithProviders(md, true, below);
                } else if (adj.containsKey(EditorWindow.RelativePosition.UP)) {
                    fem.openFileWithProviders(md, true, cur);
                } else {
                    cur.split(JSplitPane.VERTICAL_SPLIT, true, md, true, true, false);
                }
                return;
            }
        }
        new OpenFileDescriptor(project, md).navigate(true);
    }
}
