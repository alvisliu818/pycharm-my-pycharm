package local.autoclaw.mypycharm.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import local.autoclaw.mypycharm.Pure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Python 文件路径补全，对齐 vscode-my-vscode 的 completion.ts：
 * - `[[` 触发：默认提示工作区全部文件（相对内容根的路径，上限 2000）；
 *   `[[./`、`[[../`、`[[.`、`[[..` 按当前文件目录提示相对路径；
 * - 行首 `./`、`../a/b/` 触发：按当前文件目录提示相对路径；
 * - 插入时把光标后的半截词一并替换（避免残留），目录项带 `/` 尾巴。
 */
public final class MyCompletionContributor extends CompletionContributor {
    private static final Logger LOG = Logger.getInstance(MyCompletionContributor.class);
    private static final Set<String> EXTS = Set.of("py", "pyw", "pyi");
    private static final List<String> EXCLUDE_DIRS =
            List.of("node_modules", ".git", "__pycache__", ".venv", "venv", "dist", "out", "tmp", ".vs");
    private static final int MAX_FILES = 2000;

    public MyCompletionContributor() {
    }

    // ---------- 上下文检测 ----------

    /** 补全上下文；null 表示不在上下文中。 */
    public static final class Ctx {
        public int lineStart;
        public int caret;
        /** 替换区起点：wiki = `[[` 之后；relative = 已输入路径起点 */
        public int replacementStart;
        /** 光标前已输入的查询部分（wiki 模式不含 `[[`） */
        public String query;
        /** 光标后到边界（方括号/空白/引号）的半截词长度 */
        public int tailLen;
        /** true = 相对目录列表模式；false = 工作区全文件模式 */
        public boolean relative;
        /** 相对模式的目录前缀（'.' / '..' / './a/'） */
        public String dirPart;
    }

    /** 从文档文本检测上下文（offset 为原始文件中的光标位置）。 */
    @Nullable
    public static Ctx detect(Document doc, int offset) {
        if (doc == null || offset < 0 || offset > doc.getTextLength()) {
            return null;
        }
        String text = doc.getText();
        int lineStart = offset == 0 ? 0 : text.lastIndexOf('\n', offset - 1) + 1;
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String before = text.substring(lineStart, offset);
        String after = text.substring(offset, lineEnd);
        int tailLen = Pure.trailingWordLen(after);

        Matcher w = Pure.WIKI_PREFIX.matcher(before);
        if (w.find()) {
            String query = w.group().substring(2);
            Ctx c = new Ctx();
            c.lineStart = lineStart;
            c.caret = offset;
            c.replacementStart = lineStart + w.start() + 2;
            c.query = query;
            c.tailLen = tailLen;
            String dirPart = Pure.relDirPart(query);
            if (dirPart != null) {
                c.relative = true;
                c.dirPart = dirPart;
            }
            return c;
        }

        Matcher r = Pure.REL_PATH.matcher(before);
        if (r.matches()) {
            String typed = r.group();
            String dirPart = r.group(1);
            Ctx c = new Ctx();
            c.lineStart = lineStart;
            c.caret = offset;
            c.replacementStart = offset - typed.length();
            c.query = typed;
            c.tailLen = tailLen;
            c.relative = true;
            c.dirPart = dirPart;
            return c;
        }
        return null;
    }

    public static boolean isPythonish(PsiFile file) {
        if (file == null) {
            return false;
        }
        VirtualFile vf = file.getVirtualFile() != null ? file.getVirtualFile()
                : (file.getOriginalFile() != null ? file.getOriginalFile().getVirtualFile() : null);
        if (vf == null) {
            return false;
        }
        String ext = vf.getExtension();
        return ext != null && EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    // ---------- 触发字符 ----------

    @Override
    public boolean invokeAutoPopup(@Nullable PsiElement position, char typeChar) {
        if (position == null) {
            return false;
        }
        if (typeChar != '[' && typeChar != '.' && typeChar != '/' && typeChar != '\\') {
            return false;
        }
        return isPythonish(position.getContainingFile());
    }

    @Override
    public void beforeCompletion(@NotNull CompletionInitializationContext init) {
        try {
            if (!isPythonish(init.getFile())) {
                return;
            }
            Document doc = init.getEditor().getDocument();
            int offset = init.getStartOffset();
            Ctx ctx = detect(doc, offset);
            if (ctx == null) {
                return;
            }
            // 替换区从已输入部分开始（`[[` 保留在原地），插入时由 insertHandler 清掉尾部半截词
            init.setReplacementOffset(ctx.replacementStart);
        } catch (Exception e) {
            LOG.warn("[my-pycharm] beforeCompletion 异常: " + e);
        }
    }

    // ---------- 条目生成 ----------

    private static final class Provider extends CompletionProvider<CompletionParameters> {
        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
            try {
                if (!isPythonish(parameters.getOriginalFile())) {
                    return;
                }
                Document doc = parameters.getEditor().getDocument();
                Ctx ctx = detect(doc, parameters.getOffset());
                if (ctx == null) {
                    return;
                }
                CompletionResultSet rs = result.withPrefixMatcher(new ContainsMatcher(ctx.query));
                if (ctx.relative) {
                    String currentDir = dirOf(parameters.getOriginalFile());
                    if (currentDir != null) {
                        String dir = resolveRelative(currentDir, ctx.dirPart);
                        for (LookupElement el : dirItems(dir, ctx.dirPart, ctx)) {
                            rs.addElement(el);
                        }
                    }
                } else {
                    for (LookupElement el : workspaceItems(parameters.getOriginalFile().getProject(), parameters.getOriginalFile(), ctx)) {
                        rs.addElement(el);
                    }
                }
            } catch (Exception e) {
                LOG.warn("[my-pycharm] 补全异常: " + e);
            }
        }
    }

    private static String dirOf(PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        VirtualFile dir = vf != null ? vf.getParent() : null;
        return dir == null ? null : dir.getPath();
    }

    /** 以当前文件目录为基准解析 ./ 与 ../，返回目录绝对路径。 */
    static String resolveRelative(String baseDir, String dirPart) {
        String dir = baseDir;
        for (String seg : dirPart.split("/")) {
            if ("..".equals(seg)) {
                File parent = new File(dir).getParentFile();
                dir = parent == null ? dir : parent.getPath();
            }
        }
        return dir;
    }

    /** 列目录生成补全项：insertText = 前缀 + 名称 +（目录加 /），目录排前面。 */
    private static List<LookupElement> dirItems(String dir, String dirPart, Ctx ctx) {
        List<LookupElement> out = new ArrayList<>();
        File d = new File(dir);
        File[] entries = d.listFiles();
        if (entries == null) {
            return out;
        }
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File f : entries) {
            (f.isDirectory() ? dirs : files).add(f);
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String prefix = dirPart.endsWith("/") ? dirPart : dirPart + "/";
        for (File f : dirs) {
            out.add(item(prefix + f.getName() + "/", true, dir, ctx));
        }
        for (File f : files) {
            out.add(item(prefix + f.getName(), false, dir, ctx));
        }
        return out;
    }

    private static LookupElement item(String insertText, boolean isDir, String baseDir, Ctx ctx) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(insertText)
                        .withIcon(isDir ? com.intellij.icons.AllIcons.Nodes.Folder : com.intellij.icons.AllIcons.FileTypes.Any_type)
                        .withTypeText(baseDir, true)
                        .withInsertHandler(tailCleaner(ctx)),
                isDir ? 2.0 : 1.0);
    }

    /** 插入后把光标残留的半截词删掉（对齐 completion.ts 的 trailingWordLen 替换语义）。 */
    private static InsertHandler<LookupElement> tailCleaner(Ctx ctx) {
        return (InsertionContext ic, LookupElement le) -> {
            try {
                if (ctx.tailLen > 0) {
                    Document doc = ic.getDocument();
                    int s = ic.getTailOffset();
                    int e = Math.min(s + ctx.tailLen, doc.getTextLength());
                    if (e > s) {
                        doc.replaceString(s, e, "");
                    }
                }
                ic.commitDocument();
            } catch (Exception e) {
                LOG.warn("[my-pycharm] 插入后清理异常: " + e);
            }
        };
    }

    /** 工作区全文件扫描（相对内容根路径，上限 2000，排除常用目录），文件名命中查询的排前面。 */
    private static List<LookupElement> workspaceItems(Project project, PsiFile originalFile, Ctx ctx) {
        List<LookupElement> out = new ArrayList<>();
        List<VirtualFile> roots = new ArrayList<>();
        try {
            for (VirtualFile v : ProjectRootManager.getInstance(project).getContentRoots()) {
                if (v != null && v.isDirectory()) {
                    roots.add(v);
                }
            }
        } catch (Exception ignore) {
        }
        VirtualFile baseDir = originalFile.getVirtualFile();
        if (roots.isEmpty() && baseDir != null) {
            // 无内容根时回退：向上走到文件系统顶层（VfsUtilCore.getNearestRoot 在 2026.3 已不可用）
            VirtualFile top = baseDir;
            VirtualFile p;
            while ((p = top.getParent()) != null) {
                top = p;
            }
            roots.add(top);
        }
        if (roots.isEmpty()) {
            return out;
        }
        String q = Pure.lower(ctx.query);
        List<String[]> found = new ArrayList<>(); // {relPath, absPath, rootPath}
        for (VirtualFile root : roots) {
            Path rootPath = Path.of(root.getPath());
            try (var stream = Files.walk(rootPath, 12)) {
                stream.filter(Files::isRegularFile).limit(MAX_FILES * 4L).forEach(p -> {
                    if (found.size() >= MAX_FILES) {
                        return;
                    }
                    Path rel = rootPath.relativize(p);
                    if (rel.getNameCount() == 0) {
                        return;
                    }
                    boolean excluded = false;
                    for (int i = 0; i < rel.getNameCount() - 1; i++) {
                        if (EXCLUDE_DIRS.contains(rel.getName(i).toString())) {
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        return;
                    }
                    found.add(new String[]{rel.toString().replace('\\', '/'), p.toString(), root.getPath()});
                });
            } catch (Exception ignore) {
            }
            if (found.size() >= MAX_FILES) {
                break;
            }
        }
        String selfPath = baseDir != null ? Pure.lower(baseDir.getPath()) : "";
        List<String[]> matched = new ArrayList<>();
        List<String[]> others = new ArrayList<>();
        for (String[] f : found) {
            String abs = Pure.lower(f[1]);
            if (abs.equals(selfPath)) {
                continue; // 跳过当前文件自身
            }
            String name = f[0].substring(f[0].lastIndexOf('/') + 1);
            (q.isEmpty() || name.contains(q) ? matched : others).add(f);
        }
        for (String[] f : matched) {
            out.add(fullItem(f, 3.0, ctx));
        }
        for (String[] f : others) {
            out.add(fullItem(f, 1.0, ctx));
        }
        return out;
    }

    private static LookupElement fullItem(String[] f, double priority, Ctx ctx) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(f[0])
                        .withIcon(com.intellij.icons.AllIcons.FileTypes.Any_type)
                        .withTypeText(f[1], true)
                        .withInsertHandler(tailCleaner(ctx)),
                priority);
    }

    /** 包含式匹配：查询串命中路径即通过（对齐 VSCode 的 filterText contains 行为）。 */
    private static final class ContainsMatcher extends PrefixMatcher {
        ContainsMatcher(String prefix) {
            super(prefix == null ? "" : prefix);
        }

        @Override
        public boolean prefixMatches(@NotNull String name) {
            String p = getPrefix();
            if (p.isEmpty()) {
                return true;
            }
            return name.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean prefixMatches(@NotNull LookupElement element) {
            for (String s : element.getAllLookupStrings()) {
                if (prefixMatches(s)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @NotNull PrefixMatcher cloneWithPrefix(@NotNull String prefix) {
            return new ContainsMatcher(prefix);
        }
    }
}
