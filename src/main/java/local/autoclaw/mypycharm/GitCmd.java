package local.autoclaw.mypycharm;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git 命令行封装，对齐 vscode-my-vscode 的 src/git.ts：
 * 显式 --git-dir（可选 --work-tree）避免任何工作目录歧义；cwd 一律设为工作树根；
 * core.quotepath=false 保证中文文件名原样输出。
 */
public final class GitCmd {
    private GitCmd() {
    }

    public static final class Result {
        public final int code;
        public final String stdout;
        public final String stderr;

        public Result(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        public String firstStderrLine() {
            String s = stderr.trim();
            int i = s.indexOf('\n');
            return i >= 0 ? s.substring(0, i) : s;
        }
    }

    /** 以显式 --git-dir（可选 --work-tree）执行 git；args 为 git 子命令及参数。 */
    public static Result git(String gitDir, List<String> args, String workTree, long timeoutMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.add("-c");
        cmd.add("color.ui=false");
        cmd.add("--git-dir");
        cmd.add(gitDir);
        if (workTree != null && !workTree.isEmpty()) {
            cmd.add("--work-tree");
            cmd.add(workTree);
        }
        cmd.addAll(args);
        return run(cmd, workTree, timeoutMs);
    }

    /** 不带 --git-dir 的普通 git（用于判断目录是否已是自管仓库）。 */
    public static Result gitPlain(String cwd, List<String> args, long timeoutMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.addAll(args);
        return run(cmd, cwd, timeoutMs);
    }

    /** 某个 rev:path（或 :path）在对象库中是否存在。 */
    public static boolean existsInRev(String gitDir, String revPath) {
        return git(gitDir, List.of("cat-file", "-e", revPath), null, 30000).code == 0;
    }

    private static Result run(List<String> cmd, String cwd, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null && !cwd.isEmpty()) {
            pb.directory(new File(cwd));
        }
        // 避免 git 在无头环境下卡在终端凭据提示（GCM 等 GUI 凭据助手不受影响）
        pb.environment().putIfAbsent("GIT_TERMINAL_PROMPT", "0");
        try {
            Process p = pb.start();
            StreamGobbler out = new StreamGobbler(p.getInputStream());
            StreamGobbler err = new StreamGobbler(p.getErrorStream());
            Thread t1 = new Thread(out, "my-pycharm-git-out");
            Thread t2 = new Thread(err, "my-pycharm-git-err");
            t1.setDaemon(true);
            t2.setDaemon(true);
            t1.start();
            t2.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(1, "", "git 命令超时（" + timeoutMs + "ms）: " + cmd);
            }
            t1.join(5000);
            t2.join(5000);
            return new Result(p.exitValue(), out.text(), err.text());
        } catch (IOException e) {
            return new Result(1, "", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(1, "", "git 命令被中断");
        }
    }

    private static final class StreamGobbler implements Runnable {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();

        StreamGobbler(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
            } catch (IOException ignore) {
                // 进程被杀等场景，读到多少算多少
            }
        }

        String text() {
            return sb.toString();
        }
    }
}
