package local.autoclaw.mypycharm.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import local.autoclaw.mypycharm.Pure;
import org.jetbrains.annotations.NotNull;

/**
 * 打字触发补全弹出（对齐 completion.ts 的 onDidChangeTextDocument scheduleSuggest）：
 * 在 [[…]] 或 ./… 上下文里每敲一个字符都主动拉起补全；
 * 字符串/注释里 quickSuggestions 关闭、Esc 后重打也覆盖。
 */
public final class MyTypedHandler extends TypedHandlerDelegate {
    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        try {
            if (!MyCompletionContributor.isPythonish(file)) {
                return Result.CONTINUE;
            }
            int offset = editor.getCaretModel().getOffset();
            if (MyCompletionContributor.detect(editor.getDocument(), offset) == null
                    && MyCompletionContributor.detect(editor.getDocument(), Math.max(0, offset)) == null) {
                return Result.CONTINUE;
            }
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor, (PsiFile f) -> true);
        } catch (Exception ignore) {
            // 补全触发失败不影响正常输入
        }
        return Result.CONTINUE;
    }
}
