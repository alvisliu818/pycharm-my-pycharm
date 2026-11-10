package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import local.autoclaw.mypycharm.GitCmd;
import local.autoclaw.mypycharm.MyBinding;
import local.autoclaw.mypycharm.MyReposService;
import local.autoclaw.mypycharm.MyUi;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提交实现：勾选的变更逐个 git add（删除走 add -A），随后 git commit -m。
 * 对齐 scm.ts 的 stage/commit 语义。
 */
public final class MyCheckinEnvironment implements CheckinEnvironment {
    private final Project project;

    public MyCheckinEnvironment(Project project) {
        this.project = project;
    }

    @Override
    public String getHelpId() {
        return null;
    }

    @Override
    public String getCheckinOperationName() {
        return "提交";
    }

    @Override
    public boolean isRefreshAfterCommitNeeded() {
        return true;
    }

    @Override
    public List<VcsException> commit(@NotNull List<? extends Change> changes, String preparedComment) {
        return doCommit(changes, preparedComment);
    }

    @Override
    public List<VcsException> commit(@NotNull List<? extends Change> changes, String preparedComment,
                                     @NotNull CommitContext commitContext, @NotNull Set<? super String> affectedPaths) {
        return doCommit(changes, preparedComment);
    }

    private List<VcsException> doCommit(List<? extends Change> changes, String message) {
        List<VcsException> errs = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            errs.add(new VcsException("没有可提交的更改。"));
            return errs;
        }
        if (message == null || message.isBlank()) {
            errs.add(new VcsException("提交信息为空。"));
            return errs;
        }
        MyReposService svc = MyReposService.getInstance(project);

        // 按仓库分组提交（一个提交面板通常只涉及一个仓库）
        Map<MyBinding, List<Change>> byBinding = new LinkedHashMap<>();
        for (Change c : changes) {
            MyBinding b = bindingOf(svc, c);
            if (b == null) {
                errs.add(new VcsException("无法定位变更所属的分离仓库：" + c));
                continue;
            }
            byBinding.computeIfAbsent(b, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<MyBinding, List<Change>> en : byBinding.entrySet()) {
            MyBinding b = en.getKey();
            // 1) 暂存所选变更
            for (Change c : en.getValue()) {
                ContentRevision after = c.getAfterRevision();
                ContentRevision before = c.getBeforeRevision();
                String abs = (after != null ? after.getFile() : before != null ? before.getFile() : null) != null
                        ? (after != null ? after.getFile() : before.getFile()).getPath() : null;
                if (abs == null) {
                    continue;
                }
                String rel = b.toRel(abs);
                if (rel == null) {
                    continue;
                }
                List<String> args = after == null
                        ? List.of("add", "-A", "--", rel)
                        : List.of("add", "--", rel);
                GitCmd.Result r = GitCmd.git(b.gitDir(), args, b.workTree(), 60000);
                if (r.code != 0) {
                    errs.add(new VcsException(b.label() + " git add 失败（" + rel + "）:\n" + r.stderr.trim()));
                }
            }
            // 2) 确认暂存区非空
            if (GitCmd.git(b.gitDir(), List.of("diff", "--cached", "--quiet"), b.workTree(), 30000).code == 0) {
                errs.add(new VcsException(b.label() + "：暂存区为空，没有可提交的更改。"));
                continue;
            }
            // 3) 提交
            GitCmd.Result r = GitCmd.git(b.gitDir(), List.of("commit", "-m", message), b.workTree(), 120000);
            if (r.code != 0) {
                errs.add(new VcsException(b.label() + " 提交被拒绝:\n" + r.stderr.trim()));
                continue;
            }
            GitCmd.Result h = GitCmd.git(b.gitDir(), List.of("log", "-1", "--format=%h %s"), b.workTree(), 15000);
            MyUi.info(project, "My Pycharm：" + b.label() + " 已提交 " + h.stdout.trim());
        }

        svc.refreshAll();
        return errs;
    }

    @Override
    public List<VcsException> scheduleMissingFileForDeletion(@NotNull List<? extends FilePath> files) {
        // 文件已从磁盘消失 → 暂存删除
        return forEachPath(files, true);
    }

    @Override
    public List<VcsException> scheduleUnversionedFilesForAddition(@NotNull List<? extends VirtualFile> files) {
        List<VcsException> errs = new ArrayList<>();
        MyReposService svc = MyReposService.getInstance(project);
        Map<MyBinding, List<String>> groups = new LinkedHashMap<>();
        for (VirtualFile vf : files) {
            MyBinding b = svc.bindingFor(vf.getPath());
            if (b == null) {
                continue;
            }
            String rel = b.toRel(vf.getPath());
            if (rel != null) {
                groups.computeIfAbsent(b, k -> new ArrayList<>()).add(rel);
            }
        }
        for (Map.Entry<MyBinding, List<String>> en : groups.entrySet()) {
            List<String> args = new ArrayList<>();
            args.add("add");
            args.add("--");
            args.addAll(en.getValue());
            GitCmd.Result r = GitCmd.git(en.getKey().gitDir(), args, en.getKey().workTree(), 60000);
            if (r.code != 0) {
                errs.add(new VcsException(en.getKey().label() + " git add 失败:\n" + r.stderr.trim()));
            }
        }
        MyReposService.getInstance(project).refreshAll();
        return errs;
    }

    private List<VcsException> forEachPath(Collection<? extends FilePath> files, boolean deletion) {
        List<VcsException> errs = new ArrayList<>();
        MyReposService svc = MyReposService.getInstance(project);
        Map<MyBinding, List<String>> groups = new LinkedHashMap<>();
        for (FilePath fp : files) {
            MyBinding b = svc.bindingFor(fp.getPath());
            if (b == null) {
                continue;
            }
            String rel = b.toRel(fp.getPath());
            if (rel != null) {
                groups.computeIfAbsent(b, k -> new ArrayList<>()).add(rel);
            }
        }
        for (Map.Entry<MyBinding, List<String>> en : groups.entrySet()) {
            List<String> args = new ArrayList<>();
            args.add("add");
            if (deletion) {
                args.add("-A");
            }
            args.add("--");
            args.addAll(en.getValue());
            GitCmd.Result r = GitCmd.git(en.getKey().gitDir(), args, en.getKey().workTree(), 60000);
            if (r.code != 0) {
                errs.add(new VcsException(en.getKey().label() + " git add 失败:\n" + r.stderr.trim()));
            }
        }
        MyReposService.getInstance(project).refreshAll();
        return errs;
    }

    static MyBinding bindingOf(MyReposService svc, Change c) {
        ContentRevision after = c.getAfterRevision();
        ContentRevision before = c.getBeforeRevision();
        String abs = null;
        if (after != null && after.getFile() != null) {
            abs = after.getFile().getPath();
        } else if (before != null && before.getFile() != null) {
            abs = before.getFile().getPath();
        }
        return abs == null ? null : svc.bindingFor(abs);
    }
}
