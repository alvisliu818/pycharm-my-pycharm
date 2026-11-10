package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.VcsType;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import local.autoclaw.mypycharm.MyReposService;

/** 自定义 VCS：把 .git 外迁的分离仓库接进 PyCharm 版本控制体系。 */
public final class MyVcs extends AbstractVcs {
    public static final String VCS_NAME = MyReposService.VCS_NAME;
    public static final VcsKey KEY = createKey(VCS_NAME);

    private final MyChangeProvider changeProvider;
    private final MyCheckinEnvironment checkinEnvironment;
    private final MyRollbackEnvironment rollbackEnvironment;
    private final MyDiffProvider diffProvider;

    public MyVcs(Project project) {
        super(project, VCS_NAME);
        this.changeProvider = new MyChangeProvider(project);
        this.checkinEnvironment = new MyCheckinEnvironment(project);
        this.rollbackEnvironment = new MyRollbackEnvironment(project);
        this.diffProvider = new MyDiffProvider(project);
    }

    @Override
    public String getDisplayName() {
        return "My PyCharm（分离 Git 仓库）";
    }

    @Override
    public VcsType getType() {
        return VcsType.distributed;
    }

    @Override
    public ChangeProvider getChangeProvider() {
        return changeProvider;
    }

    @Override
    public CheckinEnvironment getCheckinEnvironment() {
        return checkinEnvironment;
    }

    @Override
    public RollbackEnvironment getRollbackEnvironment() {
        return rollbackEnvironment;
    }

    @Override
    public DiffProvider getDiffProvider() {
        return diffProvider;
    }

    public static MyVcs getInstance(Project project) {
        return (MyVcs) ProjectLevelVcsManager.getInstance(project).findVcsByName(VCS_NAME);
    }
}
