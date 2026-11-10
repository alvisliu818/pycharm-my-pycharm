package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManagerGate;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ChangelistBuilder;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import local.autoclaw.mypycharm.GitCmd;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import local.autoclaw.mypycharm.Pure;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 变更扫描：对每个绑定跑一次 `git status --porcelain -z -b`，
 * 条目映射为平台 Change / 未版本控制文件（对齐 scm.ts 的 refresh + toState）。
 */
public final class MyChangeProvider implements ChangeProvider {
    private static final Logger LOG = Logger.getInstance(MyChangeProvider.class);
    private final Project project;

    public MyChangeProvider(Project project) {
        this.project = project;
    }

    @Override
    public boolean isModifiedDocumentTrackingRequired() {
        return true;
    }

    @Override
    public void getChanges(@NotNull VcsDirtyScope dirtyScope,
                           @NotNull ChangelistBuilder builder,
                           @NotNull ProgressIndicator progress,
                           @NotNull ChangeListManagerGate gate) throws VcsException {
        List<MyBinding> bindings = MyReposService.getInstance(project).bindings();
        if (bindings.isEmpty()) {
            return;
        }
        progress.checkCanceled();
        for (MyBinding b : bindings) {
            progress.checkCanceled();
            progress.setText("My Pycharm：扫描 " + b.label());
            GitCmd.Result r = GitCmd.git(b.gitDir(),
                    List.of("status", "--porcelain", "-z", "-b", "--untracked-files=normal"),
                    b.workTree(), 30000);
            if (r.code != 0) {
                LOG.warn("[my-pycharm] [" + b.label() + "] git status 失败: " + r.stderr.trim());
                continue;
            }
            Pure.ParsedStatus ps = Pure.parseStatus(r.stdout);
            int count = 0;
            for (Pure.StatusEntry e : ps.entries) {
                count += emit(b, e, builder);
            }
            LOG.info("[my-pycharm] ChangeProvider: " + b.label()
                    + " 分支 " + ps.branch + "，条目 " + ps.entries.size() + "，产出 " + count);
        }
    }

    private int emit(MyBinding b, Pure.StatusEntry e, ChangelistBuilder builder) {
        boolean conflict = Pure.CONFLICT_CODES.contains(e.x + e.y);
        String newRel = e.path;
        String oldRel = e.origPath != null ? e.origPath : e.path;
        int count = 0;

        // 暂存区部分（x != ' '）：HEAD → index
        if (!e.x.equals(" ")) {
            FilePath beforePath = VcsUtil.getFilePath(b.joinFs(oldRel), false);
            FilePath afterPath = VcsUtil.getFilePath(b.joinFs(newRel), false);
            ContentRevision before = GitCmd.existsInRev(b.gitDir(), "HEAD:" + oldRel)
                    ? new MyContentRevision(b.gitDir(), "HEAD:" + oldRel, beforePath, "HEAD")
                    : null;
            ContentRevision after = e.x.equals("D")
                    ? null
                    : new MyContentRevision(b.gitDir(), ":" + newRel, afterPath, "INDEX");
            builder.processChange(new Change(before, after, statusOf(e.x, conflict)), MyVcs.KEY);
            count++;
        }

        // 工作树部分
        if (e.x.equals("?")) {
            builder.processUnversionedFile(VcsUtil.getFilePath(b.joinFs(newRel), false));
            return count + 1;
        }
        if (!e.y.equals(" ")) {
            FilePath fp = VcsUtil.getFilePath(b.joinFs(newRel), false);
            if (e.y.equals("D")) {
                // 工作树已删除（未暂存）：进入「本地删除」列表，可经回滚恢复
                builder.processLocallyDeletedFile(fp);
            } else {
                ContentRevision before = !e.x.equals(" ")
                        ? new MyContentRevision(b.gitDir(), ":" + newRel, fp, "INDEX")
                        : (GitCmd.existsInRev(b.gitDir(), "HEAD:" + newRel)
                        ? new MyContentRevision(b.gitDir(), "HEAD:" + newRel, fp, "HEAD")
                        : null);
                ContentRevision after = new CurrentContentRevision(fp);
                builder.processChange(new Change(before, after, statusOf(e.y, conflict)), MyVcs.KEY);
            }
            count++;
        }
        return count;
    }

    private static FileStatus statusOf(String code, boolean conflict) {
        if (conflict) {
            return FileStatus.MERGED_WITH_CONFLICTS;
        }
        return switch (code) {
            case "A" -> FileStatus.ADDED;
            case "D" -> FileStatus.DELETED;
            default -> FileStatus.MODIFIED;
        };
    }
}
