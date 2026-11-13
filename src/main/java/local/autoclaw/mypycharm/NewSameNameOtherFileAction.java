package local.autoclaw.mypycharm;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 右键「新建同名其他文件」：弹框预填 &lt;base&gt;.（点号留在末尾，光标停在点号后），
 * 整名可自由修改；目标已存在时自动取最小未占用序号 &lt;base&gt;_&lt;n&gt;.&lt;ext&gt;；
 * 在同目录创建空文件并打开。与「新建同名 MD 文件」的差别：扩展名现场填、可改整名、不打开已存在文件。
 */
public final class NewSameNameOtherFileAction extends AnAction {
    private static final int MAX_SEQ = 10000;

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
        String sourceName = f.getName();
        VirtualFile dir = f.getParent();
        if (dir == null) {
            MyUi.error(project, "My Pycharm：无法定位目录。");
            return;
        }
        String initial = Pure.defaultSameNameInput(sourceName);
        if (initial.isBlank()) {
            MyUi.info(project, "My Pycharm：无法从文件名 " + sourceName + " 生成默认文件名。");
            return;
        }
        FileNameDialog dialog = new FileNameDialog(project, sourceName, initial);
        if (!dialog.showAndGet()) {
            return;
        }
        String raw = dialog.getFileName();
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            MyUi.info(project, "My Pycharm：文件名为空，已取消。");
            return;
        }
        if (name.endsWith(".")) {
            MyUi.info(project, "My Pycharm：请在点号后补全扩展名。");
            return;
        }
        if (name.equals(".") || name.equals("..") || name.matches(".*[\\\\/:*?\"<>|].*")) {
            MyUi.error(project, "My Pycharm：文件名「" + name + "」含非法字符。");
            return;
        }
        // Windows 文件名大小写不敏感：占用集合统一小写比较（返回值保留用户输入的大小写）
        Set<String> taken = new HashSet<>();
        VirtualFile[] children = dir.getChildren();
        if (children != null) {
            for (VirtualFile child : children) {
                taken.add(child.getName().toLowerCase(Locale.ROOT));
            }
        }
        String target = Pure.pickSameNameTarget(name, taken, MAX_SEQ);
        if (target == null) {
            MyUi.error(project, "My Pycharm：序号已达上限（" + MAX_SEQ + "），无法生成文件名。");
            return;
        }
        String targetName = target;
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

    /** 预填 &lt;base&gt;. 的可编辑文件名输入框；光标恒在末尾（也就是点号后）。 */
    private static final class FileNameDialog extends DialogWrapper {
        private final JBTextField field = new JBTextField(32);
        private final String sourceName;

        FileNameDialog(@Nullable Project project, String sourceName, String initial) {
            super(project);
            this.sourceName = sourceName;
            setTitle("新建同名其他文件");
            field.setText(initial);
            field.setCaretPosition(initial.length());
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            panel.add(new JLabel("为 " + sourceName + " 新建同名文件（整名可改，光标已停在点号后）："), BorderLayout.NORTH);
            panel.add(field, BorderLayout.CENTER);
            JLabel hint = new JLabel("同名已存在时自动加最小序号：test.md → test_0.md");
            hint.setEnabled(false);
            panel.add(hint, BorderLayout.SOUTH);
            // 兜底：对话框显示/焦点落入输入框后仍把光标钉在末尾，避免平台默认全选
            SwingUtilities.invokeLater(() -> field.setCaretPosition(field.getText().length()));
            return panel;
        }

        @Override
        public JComponent getPreferredFocusedComponent() {
            field.setCaretPosition(field.getText().length());
            return field;
        }

        String getFileName() {
            return field.getText();
        }
    }
}
