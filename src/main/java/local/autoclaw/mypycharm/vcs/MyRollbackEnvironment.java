package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import local.autoclaw.mypycharm.GitCmd;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 回滚实现（对齐 scm.ts 的 discardNow：未跟踪 → clean，已跟踪 → checkout --）。
 * 删除文件的回滚 = 恢复文件。所有破坏性操作先弹确认框。
 */
public final class MyRollbackEnvironment implements RollbackEnvironment {
    private final Project project;

    public MyRollbackEnvironment(Project project) {
        this.project = project;
    }

    @Override
    public String getRollbackOperationName() {
        return "放弃更改";
    }

    @Override
    public void rollbackChanges(@NotNull List<? extends Change> changes,
                                @NotNull List<VcsException> errs,
                                @NotNull RollbackProgressListener listener) {
        MyReposService svc = MyReposService.getInstance(project);
        List<String> display = new ArrayList<>();
        List<String[]> jobs = new ArrayList<>(); // {gitDir, workTree, rel, kind}
        for (Change c : changes) {
            MyBinding b = MyCheckinEnvironment.bindingOf(svc, c);
            if (b == null) {
                continue;
            }
            ContentRevision after = c.getAfterRevision();
            ContentRevision before = c.getBeforeRevision();
            String abs = after != null ? after.getFile().getPath()
                    : before != null ? before.getFile().getPath() : null;
            if (abs == null) {
                continue;
            }
            String rel = b.toRel(abs);
            if (rel == null) {
                continue;
            }
            boolean inIndex = GitCmd.existsInRev(b.gitDir(), ":" + rel);
            boolean inHead = GitCmd.existsInRev(b.gitDir(), "HEAD:" + rel);
            String kind = (!inIndex && !inHead && after != null) ? "clean" : "checkout";
            jobs.add(new String[]{b.gitDir(), b.workTree(), rel, kind});
            display.add(rel);
        }
        if (!confirm(display)) {
            return;
        }
        execute(jobs, errs, listener);
    }

    @Override
    public void rollbackMissingFileDeletion(@NotNull List<? extends FilePath> files,
                                            @NotNull List<? super VcsException> errs,
                                            @NotNull RollbackProgressListener listener) {
        // 磁盘上缺失的已跟踪文件：回滚 = 恢复文件（checkout --）
        MyReposService svc = MyReposService.getInstance(project);
        List<String> display = new ArrayList<>();
        List<String[]> jobs = new ArrayList<>();
        for (FilePath fp : files) {
            MyBinding b = svc.bindingFor(fp.getPath());
            if (b == null) {
                continue;
            }
            String rel = b.toRel(fp.getPath());
            if (rel == null) {
                continue;
            }
            jobs.add(new String[]{b.gitDir(), b.workTree(), rel, "checkout"});
            display.add(rel);
        }
        if (!confirm(display)) {
            return;
        }
        execute(jobs, errs, listener);
    }

    @Override
    public void rollbackModifiedWithoutCheckout(@NotNull List<? extends VirtualFile> files,
                                                @NotNull List<? super VcsException> errs,
                                                @NotNull RollbackProgressListener listener) {
        MyReposService svc = MyReposService.getInstance(project);
        List<String> display = new ArrayList<>();
        List<String[]> jobs = new ArrayList<>();
        for (VirtualFile vf : files) {
            MyBinding b = svc.bindingFor(vf.getPath());
            if (b == null) {
                continue;
            }
            String rel = b.toRel(vf.getPath());
            if (rel == null) {
                continue;
            }
            boolean inIndex = GitCmd.existsInRev(b.gitDir(), ":" + rel);
            boolean inHead = GitCmd.existsInRev(b.gitDir(), "HEAD:" + rel);
            String kind = (inIndex || inHead) ? "checkout" : "clean";
            jobs.add(new String[]{b.gitDir(), b.workTree(), rel, kind});
            display.add(rel);
        }
        if (!confirm(display)) {
            return;
        }
        execute(jobs, errs, listener);
    }

    private boolean confirm(List<String> display) {
        if (display.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder("放弃以下更改（不可恢复）？\n");
        for (int i = 0; i < display.size() && i < 8; i++) {
            sb.append(display.get(i)).append('\n');
        }
        if (display.size() > 8) {
            sb.append("… 共 ").append(display.size()).append(" 个文件\n");
        }
        sb.append("\n未跟踪文件将被直接删除。");
        int ok = Messages.showYesNoDialog(project, sb.toString(), "放弃更改", "放弃更改", "取消",
                Messages.getWarningIcon());
        return ok == Messages.YES;
    }

    private void execute(List<String[]> jobs, List<? super VcsException> errs, RollbackProgressListener listener) {
        listener.indeterminate();
        for (String[] job : jobs) {
            listener.checkCanceled();
            List<String> args = "clean".equals(job[3])
                    ? List.of("clean", "-f", "-d", "--", job[2])
                    : List.of("checkout", "--", job[2]);
            GitCmd.Result r = GitCmd.git(job[0], args, job[1], 60000);
            if (r.code != 0) {
                errs.add(new VcsException("放弃更改失败（" + job[2] + "）:\n" + r.stderr.trim()));
            }
        }
        MyReposService.getInstance(project).refreshAll();
    }
}
