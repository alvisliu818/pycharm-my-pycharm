package local.autoclaw.mypycharm;

import java.io.File;
import java.util.Locale;

/**
 * 一个分离仓库绑定：workTree（工作树）+ gitDir（外迁的 .git 目录）。
 * 对齐 vscode-my-vscode 的 Binding。
 *
 * @param workTree 项目工作树目录
 * @param gitDir   外迁的 .git 目录
 * @param label    显示名称（默认取工作树目录名）
 * @param force    跳过自管检测强制接管
 */
public record MyBinding(String workTree, String gitDir, String label, boolean force) {

    /** rel 为 POSIX 风格相对路径（'/' 分隔），返回工作树下的绝对路径。 */
    public String joinFs(String rel) {
        String wt = Pure.normalizePath(workTree);
        String base = wt == null ? workTree : wt;
        return base + "/" + rel.replace('\\', '/');
    }

    /** 绝对路径 → 相对工作树的 POSIX 路径；不属于该工作树时返回 null（大小写不敏感，Windows 友好）。 */
    public String toRel(String fsPath) {
        String a = Pure.normalizePath(workTree);
        String b = Pure.normalizePath(fsPath);
        if (a == null || b == null) {
            return null;
        }
        String al = a.toLowerCase(Locale.ROOT);
        String bl = b.toLowerCase(Locale.ROOT);
        if (!bl.startsWith(al + "/")) {
            return null;
        }
        return b.substring(a.length() + 1);
    }

    public static String basename(String p) {
        String n = Pure.normalizePath(p);
        if (n == null) {
            return p;
        }
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    @Override
    public String toString() {
        return label + "（work-tree=" + workTree + " git-dir=" + gitDir + "）";
    }

    static {
        // 触及 File 以免误删 import（joinFs 语义保持 POSIX，供 git 命令行与 VcsUtil 使用）
        new File(".").getAbsoluteFile();
    }
}
