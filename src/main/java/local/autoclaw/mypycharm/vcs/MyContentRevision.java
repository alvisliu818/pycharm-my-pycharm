package local.autoclaw.mypycharm.vcs;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import local.autoclaw.mypycharm.GitCmd;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * git 对象内容版本：spec 形如 HEAD:path / :path（暂存区）/ commit:path。
 * 惰性求值，供差异视图按需读取。
 */
public final class MyContentRevision implements ContentRevision, ByteBackedContentRevision {
    private final String gitDir;
    private final String spec;
    private final FilePath file;
    private final VcsRevisionNumber number;

    public MyContentRevision(String gitDir, String spec, FilePath file, String revDisplay) {
        this.gitDir = gitDir;
        this.spec = spec;
        this.file = file;
        this.number = new Rev(revDisplay);
    }

    @Override
    public byte[] getContentAsBytes() throws VcsException {
        GitCmd.Result r = GitCmd.git(gitDir, List.of("show", spec), null, 30000);
        if (r.code != 0) {
            throw new VcsException("无法读取 " + spec + "：" + r.stderr.trim());
        }
        return r.stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String getContent() throws VcsException {
        byte[] bytes = getContentAsBytes();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public FilePath getFile() {
        return file;
    }

    @Override
    public VcsRevisionNumber getRevisionNumber() {
        return number;
    }

    /** 极简修订号（HEAD / 索引 / 短哈希），只用于展示与比较占位。 */
    public static final class Rev implements VcsRevisionNumber {
        private final String s;

        public Rev(String s) {
            this.s = s;
        }

        @Override
        public String asString() {
            return s;
        }

        @Override
        public int compareTo(VcsRevisionNumber o) {
            return s.compareTo(o.asString());
        }

        @Override
        public String toString() {
            return s;
        }
    }

    public static final Rev REV_HEAD = new Rev("HEAD");

    @Override
    public String toString() {
        return spec;
    }

    @Nullable
    public static ContentRevision rev(String gitDir, String spec, FilePath file, String revDisplay) {
        return new MyContentRevision(gitDir, spec, file, revDisplay);
    }
}
