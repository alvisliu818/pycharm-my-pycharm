package local.autoclaw.mypycharm;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置存储（对齐 myVscode.autoDiscover / myVscode.bindings）：
 * ① 各根目录下 *-gl.sh 包装脚本自动发现（autoDiscover，默认开）；
 * ② 手动绑定补充（bindings 列表）。
 */
@Service(Service.Level.PROJECT)
@State(name = "MyPycharmSettings", storages = @Storage("MyPycharmSettings.xml"))
public final class MySettings implements PersistentStateComponent<MySettings.State> {

    public static final class BindingDef {
        public String workTree;
        public String gitDir;
        public String label;
        public boolean force;

        public BindingDef() {
        }

        public BindingDef(String workTree, String gitDir, String label, boolean force) {
            this.workTree = workTree;
            this.gitDir = gitDir;
            this.label = label;
            this.force = force;
        }
    }

    public static final class State {
        public boolean autoDiscover = true;
        public boolean openMdInSplitDown = true;
        public boolean sameNameFileCopyContent = false;
        public List<BindingDef> bindings = new ArrayList<>();
    }

    private final State state = new State();

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(State s) {
        state.autoDiscover = s.autoDiscover;
        state.openMdInSplitDown = s.openMdInSplitDown;
        state.sameNameFileCopyContent = s.sameNameFileCopyContent;
        state.bindings = s.bindings == null ? new ArrayList<>() : s.bindings;
    }

    public static MySettings getInstance(Project project) {
        return project.getService(MySettings.class);
    }
}
