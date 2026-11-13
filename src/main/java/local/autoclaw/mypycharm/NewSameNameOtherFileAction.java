package local.autoclaw.mypycharm;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 右键「新建同名其他文件」：弹出输入框手动填扩展名（md/.md 均可），
 * 在同目录创建 <base>.<ext>（已存在则直接打开），然后打开编辑。
 * 与「新建同名 MD 文件」的差别仅在于扩展名不固定，由用户现场输入。
 */
public final class NewSameNameOtherFileAction extends AnAction {
    public NewSameNameOtherFileAction() {
        super("新建同名其他文件...");
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile f = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(f != null && !f.isDirectory());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        VirtualFile f = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Project project = e.getProject();
        if (f == null || project == null) {
            return;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isBlank()) {
            MyUi.info(project, "My Pycharm：无法从文件名 " + name + " 生成同名文件名。");
            return;
        }
        VirtualFile dir = f.getParent();
        if (dir == null) {
            MyUi.error(project, "My Pycharm：无法定位目录。");
            return;
        }
        String ext = Messages.showInputDialog(project,
                "为 " + name + " 新建同名文件，输入扩展名（不带点也行）：",
                "新建同名其他文件",
                Messages.getQuestionIcon(), "", null);
        if (ext == null) {
            return;
        }
        ext = ext.trim();
        while (ext.startsWith(".")) {
            ext = ext.substring(1).trim();
        }
        if (ext.isEmpty()) {
            MyUi.info(project, "My Pycharm：扩展名为空，已取消。");
            return;
        }
        if (ext.matches(".*[\\\\/:*?\"<>|].*") || ext.contains("..")) {
            MyUi.error(project, "My Pycharm：扩展名「" + ext + "」含非法字符。");
            return;
        }
        String targetName = base + "." + ext;
        VirtualFile existing = dir.findChild(targetName);
        if (existing != null) {
            MyUi.info(project, "My Pycharm：" + targetName + " 已存在，直接打开。");
            new OpenFileDescriptor(project, existing).navigate(true);
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                dir.createChildData(this, targetName);
            } catch (IOException ex) {
                MyUi.error(project, "My Pycharm：创建 " + targetName + " 失败：" + ex.getMessage());
            }
        });
        VirtualFile created = dir.findChild(targetName);
        if (created != null) {
            MyUi.info(project, "My Pycharm：已创建 " + targetName + "。");
            new OpenFileDescriptor(project, created).navigate(true);
        }
    }
}
