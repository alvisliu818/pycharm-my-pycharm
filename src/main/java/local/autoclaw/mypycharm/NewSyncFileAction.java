package local.autoclaw.mypycharm;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * 右键「生成同步文件」：在同目录生成 <base>_<n>.<ext>（n 取最小未占用序号，
 * test.py → test_0.py → test_1.py…）；设置「生成同步文件时复制原文件内容」
 * 开启时复制原文件内容，默认创建空文件。生成后直接打开。
 */
public final class NewSyncFileAction extends AnAction {
    private static final int MAX_SEQ = 10000;

    public NewSyncFileAction() {
        super("生成同步文件");
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
        String ext = dot > 0 ? name.substring(dot) : "";
        if (base.isBlank()) {
            MyUi.info(project, "My Pycharm：无法从文件名 " + name + " 生成同步文件名。");
            return;
        }
        VirtualFile dir = f.getParent();
        if (dir == null) {
            MyUi.error(project, "My Pycharm：无法定位目录。");
            return;
        }
        String target = null;
        for (int n = 0; n < MAX_SEQ; n++) {
            String cand = base + "_" + n + ext;
            if (dir.findChild(cand) == null) {
                target = cand;
                break;
            }
        }
        if (target == null) {
            MyUi.error(project, "My Pycharm：序号已达上限（" + MAX_SEQ + "），无法生成同步文件。");
            return;
        }
        byte[] content = null;
        if (MySettings.getInstance(project).getState().syncFileCopyContent) {
            try (InputStream in = f.getInputStream()) {
                content = in.readAllBytes();
            } catch (IOException ex) {
                MyUi.error(project, "My Pycharm：读取 " + name + " 失败：" + ex.getMessage());
                return;
            }
        }
        byte[] bytes = content;
        String targetName = target;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                VirtualFile child = dir.createChildData(this, targetName);
                if (bytes != null) {
                    child.setBinaryContent(bytes);
                }
            } catch (IOException ex) {
                MyUi.error(project, "My Pycharm：创建 " + targetName + " 失败：" + ex.getMessage());
            }
        });
        VirtualFile created = dir.findChild(targetName);
        if (created != null) {
            MyUi.info(project, "My Pycharm：已生成同步文件 " + targetName
                    + (bytes != null ? "（复制原内容）" : "（空文件）") + "。");
            new OpenFileDescriptor(project, created).navigate(true);
        }
    }
}
