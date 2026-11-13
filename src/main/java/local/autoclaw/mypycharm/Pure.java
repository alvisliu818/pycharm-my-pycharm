package local.autoclaw.mypycharm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯逻辑：不依赖 IntelliJ 平台，可直接用 java 跑冒烟测试。
 * 与 vscode-my-vscode 的 src/pure.ts、src/graph.ts、src/completion.ts 中对应函数逐一对齐。
 */
public final class Pure {
    private Pure() {
    }

    /** 统一路径分隔符为 '/'，去掉尾部斜杠（根路径除外）。 */
    public static String normalizePath(String p) {
        if (p == null) {
            return null;
        }
        String s = p.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    // ---------- 包装脚本解析（binding.ts + pure.ts） ----------

    public static final class WrapperMatch {
        public final String gitDir;
        public final String workTree;

        public WrapperMatch(String gitDir, String workTree) {
            this.gitDir = gitDir;
            this.workTree = workTree;
        }
    }

    private static final Pattern GIT_DIR_RE =
            Pattern.compile("--git-dir\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))");
    private static final Pattern WORK_TREE_RE =
            Pattern.compile("--work-tree\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))");

    /**
     * 解析 *-gl.sh 包装脚本中的 --git-dir / --work-tree。
     * 注释行忽略；两者都齐才算有效绑定（纯转发脚本会被自然跳过）。
     */
    public static WrapperMatch parseWrapperScript(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            if (line.matches("\\s*#.*")) {
                continue;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);
        }
        String b = body.toString();
        return new WrapperMatch(pick(GIT_DIR_RE, b), pick(WORK_TREE_RE, b));
    }

    private static String pick(Pattern re, String body) {
        Matcher m = re.matcher(body);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
        return normalizePath(v);
    }

    /** *-gl.sh / gl.sh 文件名判定（binding.ts 的 SCRIPT_RE）。 */
    public static boolean isWrapperScriptName(String name) {
        return name != null && name.matches("(?:[\\w.-]+-)?gl\\.sh");
    }

    // ---------- git status --porcelain -z 解析（pure.ts） ----------

    public static final class StatusEntry {
        /** index 状态位（porcelain v1 X） */
        public final String x;
        /** 工作树状态位（porcelain v1 Y） */
        public final String y;
        /** 相对工作树的 POSIX 路径（重命名/复制时为新路径） */
        public final String path;
        /** 重命名/复制时的原路径 */
        public final String origPath;

        public StatusEntry(String x, String y, String path, String origPath) {
            this.x = x;
            this.y = y;
            this.path = path;
            this.origPath = origPath;
        }
    }

    public static final class ParsedStatus {
        public String branch;
        /** 上游分支（如 origin/main），无 upstream 时为 null */
        public String upstream;
        public Integer ahead;
        public Integer behind;
        public final List<StatusEntry> entries = new ArrayList<>();
    }

    /** porcelain v1 的冲突码（XY 组合）。 */
    public static final Set<String> CONFLICT_CODES =
            Set.of("DD", "AU", "UD", "UA", "DU", "AA", "UU");

    private static final Pattern AHEAD_RE = Pattern.compile("\\[ahead (\\d+)(?:, behind (\\d+))?\\]");
    private static final Pattern BEHIND_RE = Pattern.compile("\\[behind (\\d+)\\]");

    /** 解析 `git status --porcelain -z -b` 的输出。 */
    public static ParsedStatus parseStatus(String zText) {
        ParsedStatus out = new ParsedStatus();
        String[] fields = zText.split("\0", -1);
        int i = 0;
        if (fields.length > 0 && fields[0].startsWith("## ")) {
            String head = fields[0].substring(3).trim();
            Integer ahead = null;
            Integer behind = null;
            Matcher m1 = AHEAD_RE.matcher(head);
            Matcher m2 = BEHIND_RE.matcher(head);
            if (m1.find()) {
                ahead = Integer.parseInt(m1.group(1));
                if (m1.group(2) != null) {
                    behind = Integer.parseInt(m1.group(2));
                }
            } else if (m2.find()) {
                behind = Integer.parseInt(m2.group(1));
            }
            out.ahead = ahead;
            out.behind = behind;
            String b0 = head;
            String up = null;
            int dots = head.indexOf("...");
            if (dots >= 0) {
                b0 = head.substring(0, dots);
                up = head.substring(dots + 3);
            }
            if (up != null) {
                int sp = up.indexOf(' ');
                String u = sp >= 0 ? up.substring(0, sp) : up;
                out.upstream = u.isEmpty() ? null : u;
            }
            String br = b0;
            int bi = br.indexOf(" [");
            if (bi >= 0) {
                br = br.substring(0, bi);
            }
            br = br.trim();
            if (br.startsWith("No commits yet on ")) {
                br = br.substring("No commits yet on ".length()).trim();
            }
            out.branch = br.isEmpty() ? null : br;
            i = 1;
        }
        for (; i < fields.length; i++) {
            String f = fields[i];
            if (f == null || f.length() < 3 || f.charAt(2) != ' ') {
                continue;
            }
            String x = f.substring(0, 1);
            String y = f.substring(1, 2);
            String p = f.substring(3).replace('\\', '/');
            String orig = null;
            if (x.equals("R") || x.equals("C")) {
                i += 1;
                String o = i < fields.length ? fields[i] : "";
                o = o.replace('\\', '/');
                orig = o.isEmpty() ? null : o;
            }
            out.entries.add(new StatusEntry(x, y, p, orig));
        }
        return out;
    }

    public static final class GroupedStatus {
        public final List<StatusEntry> index = new ArrayList<>();
        public final List<StatusEntry> worktree = new ArrayList<>();
        public final List<StatusEntry> conflict = new ArrayList<>();
    }

    /** 把 status 条目拆进「暂存 / 工作树 / 冲突」三组；MM 这类条目会同时进两组。 */
    public static GroupedStatus splitGroups(List<StatusEntry> entries) {
        GroupedStatus g = new GroupedStatus();
        for (StatusEntry e : entries) {
            if (CONFLICT_CODES.contains(e.x + e.y)) {
                g.conflict.add(e);
            } else if (e.x.equals("?")) {
                g.worktree.add(e);
            } else {
                if (!e.x.equals(" ")) {
                    g.index.add(e);
                }
                if (!e.y.equals(" ")) {
                    g.worktree.add(e);
                }
            }
        }
        return g;
    }

    // ---------- 补全上下文（completion.ts） ----------

    /** 行尾形如 [[xxx 的前缀（xxx 内不允许再出现方括号） */
    public static final Pattern WIKI_PREFIX = Pattern.compile("\\[\\[[^\\[\\]]*$");

    /** 行尾形如 ./xxx 或 ../a/b/xxx 的相对路径（引号、空白、反斜杠视为路径结束） */
    public static final Pattern REL_PATH =
            Pattern.compile("^(\\.{1,2}/(?:[^'\"/\\s]+/)*)([^'\"/\\s]*)$");

    /** query 是否处于相对路径输入状态；返回目录前缀部分（'.' / '..' / './…/'），否则 null。 */
    public static String relDirPart(String q) {
        if (".".equals(q) || "..".equals(q)) {
            return q;
        }
        Matcher m = REL_PATH.matcher(q);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    /** 光标后的同词尾巴长度（到方括号/空白/引号为止），补全时一并替换，避免插入后残留半截词。 */
    public static int trailingWordLen(String rest) {
        int n = 0;
        while (n < rest.length()) {
            char c = rest.charAt(n);
            if (c == '[' || c == ']' || c == '\'' || c == '"' || c == '`' || Character.isWhitespace(c)) {
                break;
            }
            n++;
        }
        return n;
    }

    // ---------- 提交图表（graph.ts） ----------

    public static final class GraphCommit {
        public final String graph;
        public final String id;
        public final List<String> parents;
        public final String shortId;
        public final String author;
        public final long time;
        public final String subject;

        public GraphCommit(String graph, String id, String[] parents, String shortId, String author, long time, String subject) {
            this.graph = graph;
            this.id = id;
            this.parents = List.of(parents);
            this.shortId = shortId;
            this.author = author;
            this.time = time;
            this.subject = subject;
        }
    }

    /** 解析 `git log --graph --pretty=format:%x01...%x1f...` 的输出。 */
    public static List<GraphCommit> parseGraphLog(String stdout) {
        List<GraphCommit> out = new ArrayList<>();
        for (String line : stdout.split("\r?\n", -1)) {
            int i = line.indexOf('\u0001');
            if (i < 0) {
                continue; // 提交体续行（graph 补白），忽略
            }
            String graph = line.substring(0, i);
            String[] f = line.substring(i + 1).split("\u001f");
            if (f.length < 6 || f[0].isEmpty()) {
                continue;
            }
            String[] parents = f[1].isEmpty() ? new String[0] : f[1].split(" ");
            long t = 0;
            try {
                t = Long.parseLong(f[4].trim());
            } catch (NumberFormatException ignore) {
            }
            out.add(new GraphCommit(graph, f[0], parents, f[2], f[3], t, f[5]));
        }
        return out;
    }

    public static String relTime(long sec) {
        long d = System.currentTimeMillis() / 1000 - sec;
        if (d < 60) {
            return "刚刚";
        }
        if (d < 3600) {
            return (d / 60) + " 分钟前";
        }
        if (d < 86400) {
            return (d / 3600) + " 小时前";
        }
        if (d < 86400L * 30) {
            return (d / 86400) + " 天前";
        }
        if (d < 86400L * 365) {
            return (d / 86400 / 30) + " 个月前";
        }
        return (d / 86400 / 365) + " 年前";
    }

    // ---------- 新建同名其他文件（文件名生成） ----------

    /** 弹框默认输入：去掉原扩展名，保留 base 并补一个点号（光标随后落在点号后）。 */
    public static String defaultSameNameInput(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base + ".";
    }

    /**
     * 目标文件名：未被占用则原样返回；已占用则取 &lt;base&gt;_&lt;最小未占用序号&gt;&lt;ext&gt;；
     * 序号用尽返回 null。taken 为小写文件名集合（Windows 文件名大小写不敏感），
     * 返回值保留用户输入的大小写。
     */
    public static String pickSameNameTarget(String rawName, Set<String> taken, int maxSeq) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return null;
        }
        Set<String> used = taken == null ? Set.of() : taken;
        if (!used.contains(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 0; n < maxSeq; n++) {
            String cand = base + "_" + n + ext;
            if (!used.contains(cand.toLowerCase(Locale.ROOT))) {
                return cand;
            }
        }
        return null;
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
