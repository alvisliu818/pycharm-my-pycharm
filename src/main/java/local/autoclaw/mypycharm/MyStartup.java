package local.autoclaw.mypycharm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** 项目启动：后台发现绑定并应用目录映射。 */
public final class MyStartup implements ProjectActivity {
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        MyReposService.getInstance(project).bootstrap();
        return Unit.INSTANCE;
    }
}
