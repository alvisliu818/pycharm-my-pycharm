package local.autoclaw.mypycharm.graph;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import local.autoclaw.mypycharm.graph.MyGraphPanel;
import org.jetbrains.annotations.NotNull;

/** 提交图表工具窗（对齐 vscode-my-vscode 的活动栏「提交图表」视图）。 */
public final class MyGraphToolWindowFactory implements ToolWindowFactory {
    public static final String ID = "MyPycharmGraph";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MyGraphPanel panel = new MyGraphPanel(project, toolWindow);
        ContentManager cm = toolWindow.getContentManager();
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "提交图表", false);
        cm.addContent(content);
        Disposer.register(content, panel);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
