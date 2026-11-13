package local.autoclaw.mypycharm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 纯逻辑冒烟测试，对齐 vscode-my-vscode 的 test/smoke.mjs：
 * ① 真实包装脚本解析（myobsidian-gl.sh 命中、gl.sh 转发脚本跳过）；
 * ② 合成 status 解析（中文路径/重命名/双状态/删除）；
 * ③ 真实分离仓库只读探测（与运行时完全一致的命令）。
 * 直接 `java local.autoclaw.mypycharm.PureSelfTest [codeRoot]` 运行。
 */
public final class PureSelfTest {
    private static int failures = 0;

    public static void main(String[] args) {
        String code = args.length > 0 ? args[0] : "E:/HOME/Code";

        // 1) 真实包装脚本解析
        try {
            String my = Files.readString(Path.of(code, "myobsidian-gl.sh"));
            Pure.WrapperMatch m = Pure.parseWrapperScript(my);
            eq(m.gitDir, "E:/HOME/Code/obsidian-my-obsidian-git", "myobsidian gitDir");
            eq(m.workTree, "E:/HOME/Code/obsidian/obsidian-my-obsidian", "myobsidian workTree");
            String fwd = Files.readString(Path.of(code, "gl.sh"));
            Pure.WrapperMatch f = Pure.parseWrapperScript(fwd);
            check(f.gitDir == null && f.workTree == null, "转发脚本（gl.sh）应被自然跳过");
            System.out.println("OK myobsidian-gl.sh 解析正确；gl.sh 转发脚本被跳过");
            System.out.println("  — Code 根目录全部 gl 脚本的发现结果 —");
            try (var s = Files.list(Path.of(code))) {
                s.map(p -> p.getFileName().toString())
                        .filter(Pure::isWrapperScriptName)
                        .sorted()
                        .forEach(name -> {
                            try {
                                Pure.WrapperMatch w = Pure.parseWrapperScript(Files.readString(Path.of(code, name)));
                                System.out.println("  " + name + " → "
                                        + (w.gitDir != null ? w.workTree + " ⇄ " + w.gitDir : "(转发/无绑定，跳过)"));
                            } catch (Exception e) {
                                System.out.println("  " + name + " → (读取失败: " + e.getMessage() + ")");
                            }
                        });
            }
        } catch (Exception e) {
            fail("真实包装脚本解析失败: " + e);
        }

        // 2) 合成 status：中文路径 + 重命名 + 暂存后又修改 + 删除 + 未跟踪
        String z = "## main...origin/main [ahead 1, behind 2]\0MM 工作/文件.md\0R  新位置/新名.md\0旧位置/旧名.md\0?? 未跟踪 新文件.md\0 D 删了.md\0";
        Pure.ParsedStatus parsed = Pure.parseStatus(z);
        eq(parsed.branch, "main", "branch");
        eq(String.valueOf(parsed.ahead), "1", "ahead");
        eq(String.valueOf(parsed.behind), "2", "behind");
        eq(String.valueOf(parsed.entries.size()), "4", "entries");
        Pure.GroupedStatus g = Pure.splitGroups(parsed.entries);
        check(g.index.size() == 2, "index 组应为 MM + R，实际 " + g.index.size());
        check(g.worktree.size() == 3, "worktree 组应为 MM + ?? + D，实际 " + g.worktree.size());
        Pure.StatusEntry ren = parsed.entries.stream().filter(e -> e.x.equals("R")).findFirst().orElse(null);
        check(ren != null && "旧位置/旧名.md".equals(ren.origPath), "重命名原路径解析");
        System.out.println("OK status 解析（中文路径/重命名/双状态/删除）正确");

        // 3) 真实分离仓库只读探测：与插件运行时的命令完全一致
        try {
            GitCmd.Result r = GitCmd.git(
                    "E:/HOME/Code/obsidian-my-obsidian-git",
                    List.of("status", "--porcelain", "-z", "-b", "--untracked-files=normal"),
                    "E:/HOME/Code/obsidian/obsidian-my-obsidian", 30000);
            if (r.code == 0) {
                Pure.ParsedStatus real = Pure.parseStatus(r.stdout);
                System.out.println("OK 真实 myobsidian 仓库只读探测：分支 " + real.branch
                        + "，变更条目 " + real.entries.size());
            } else {
                System.out.println("（提示）真实仓库探测未成功（git 不可用或路径变化）：" + r.firstStderrLine());
            }
        } catch (Exception e) {
            System.out.println("（提示）真实仓库探测异常：" + e);
        }

        // 补充：补全上下文检测（对齐 completion.ts：[[ 可在行中；./ 需在行首）
        String doc = "text = \"[[src/ma"; // text = "[[src/ma"]]
        int offset = doc.length();
        MyCompletionCtxCheck(doc, offset, "src/ma", false);
        doc = "./sub/";
        offset = doc.length();
        MyCompletionCtxCheck(doc, offset, "./sub/", true);
        System.out.println("OK 补全上下文检测正确");

        // 4) 新建同名其他文件：默认输入（base + 点号）与同名最小序号
        eq(Pure.defaultSameNameInput("test.py"), "test.", "默认输入保留 base + 点号");
        eq(Pure.defaultSameNameInput("Makefile"), "Makefile.", "无扩展名默认输入");
        eq(Pure.defaultSameNameInput("a.b.py"), "a.b.", "多段扩展名默认输入");
        eq(Pure.pickSameNameTarget("test.md", java.util.Set.of(), 10000), "test.md", "未占用原样返回");
        eq(Pure.pickSameNameTarget(" test.md ", java.util.Set.of(), 10000), "test.md", "输入 trim");
        eq(Pure.pickSameNameTarget("test.md", java.util.Set.of("test.md"), 10000), "test_0.md", "同名加序号");
        eq(Pure.pickSameNameTarget("test.md", java.util.Set.of("test.md", "test_0.md", "test_2.md"), 10000),
                "test_1.md", "取最小未占用序号（跳过空洞）");
        eq(Pure.pickSameNameTarget("bar.txt", java.util.Set.of("bar.txt", "bar_0.txt"), 10000),
                "bar_1.txt", "整名修改后仍按新名加序号");
        eq(Pure.pickSameNameTarget("noext", java.util.Set.of("noext"), 10000), "noext_0", "无扩展名也能加序号");
        eq(Pure.pickSameNameTarget("TEST.md", java.util.Set.of("test.md"), 10000), "TEST_0.md", "大小写不敏感比较、保留原大小写");
        java.util.Set<String> full = new java.util.HashSet<>();
        full.add("t.md");
        for (int i = 0; i < 10000; i++) {
            full.add("t_" + i + ".md");
        }
        check(Pure.pickSameNameTarget("t.md", full, 10000) == null, "序号用尽返回 null");
        System.out.println("OK 新建同名其他文件：默认输入 + 同名最小序号（含上限）正确");

        if (failures > 0) {
            System.out.println(failures + " 项断言失败");
            System.exit(1);
        }
        System.out.println("全部冒烟测试通过");
    }

    private static void MyCompletionCtxCheck(String docText, int offset, String expectQuery, boolean expectRelative) {
        // 借助 Pure 的正则复刻 detect() 的核心判定（不依赖平台 Document）
        int lineStart = docText.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        String before = docText.substring(lineStart, offset);
        String query;
        boolean relative;
        java.util.regex.Matcher w = Pure.WIKI_PREFIX.matcher(before);
        if (w.find()) {
            query = w.group().substring(2);
            relative = Pure.relDirPart(query) != null;
        } else {
            java.util.regex.Matcher r = Pure.REL_PATH.matcher(before);
            if (!r.matches()) {
                fail("补全上下文未命中: " + before);
                return;
            }
            query = r.group();
            relative = true;
        }
        eq(query, expectQuery, "query（" + before + "）");
        eq(String.valueOf(relative), String.valueOf(expectRelative), "relative（" + before + "）");
    }

    private static void eq(Object actual, Object expect, String what) {
        boolean ok = actual == null ? expect == null : String.valueOf(actual).equals(String.valueOf(expect));
        check(ok, what + " 期望 [" + expect + "] 实际 [" + actual + "]");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("FAIL " + msg);
    }

    private PureSelfTest() {
    }

    static {
        // 防止 Locale 未使用告警（Windows 路径小写比较语义与运行时一致）
        Locale.getDefault();
    }
}
