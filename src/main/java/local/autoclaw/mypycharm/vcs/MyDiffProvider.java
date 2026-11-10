package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import org.jetbrains.annotations.Nullable;

/** HEAD 内容差异源：让「与最新版本比较」等平台功能对分离仓库生效。 */
public final class MyDiffProvider implements DiffProvider {
    private final Project project;

    public MyDiffProvider(Project project) {
        this.project = project;
    }

    @Override
    public VcsRevisionNumber getCurrentRevision(VirtualFile file) {
        return MyContentRevision.REV_HEAD;
    }

    @Override
    public ItemLatestState getLastRevision(VirtualFile file) {
        return new ItemLatestState(MyContentRevision.REV_HEAD, true, true);
    }

    @Override
    public ItemLatestState getLastRevision(FilePath file) {
        return new ItemLatestState(MyContentRevision.REV_HEAD, true, true);
    }

    @Override
    public VcsRevisionNumber getLatestCommittedRevision(VirtualFile file) {
        return MyContentRevision.REV_HEAD;
    }

    @Override
    public ContentRevision createFileContent(VcsRevisionNumber revisionNumber, VirtualFile file) {
        MyBinding b = MyReposService.getInstance(project).bindingFor(file.getPath());
        if (b == null) {
            return null;
        }
        String rel = b.toRel(file.getPath());
        if (rel == null) {
            return null;
        }
        return new MyContentRevision(b.gitDir(), "HEAD:" + rel, VcsUtil.getFilePath(file), "HEAD");
    }
}
