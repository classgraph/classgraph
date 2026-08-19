package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileUtils#sanitizeEntryPath(String, boolean, boolean)}: {@code "."} and {@code ".."} segments and
 * empty segments must be normalized away, wherever they appear in the path -- including as the final segment, where
 * they are not followed by a separator.
 */
public class FileUtilsSanitizeEntryPathTest {
    /**
     * Sanitize a path, without stripping leading or trailing slashes, so that the normalization itself is what is
     * under test.
     *
     * @param path
     *            the path to sanitize
     * @return the sanitized path
     */
    private static String sanitize(final String path) {
        return FileUtils.sanitizeEntryPath(path, /* removeInitialSlash = */ false, /* removeFinalSlash = */ false);
    }

    /** A {@code "."} or {@code ".."} segment in the middle of a path is normalized away. */
    @Test
    public void dotSegmentsInMiddleOfPathAreNormalized() {
        assertThat(sanitize("./a")).isEqualTo("a");
        assertThat(sanitize("../a")).isEqualTo("a");
        assertThat(sanitize("a/./b")).isEqualTo("a/b");
        assertThat(sanitize("a/b/../c")).isEqualTo("a/c");
    }

    /**
     * A trailing {@code "."} or {@code ".."} segment is normalized away too. These are not followed by a separator,
     * so a detector that only looks for the substrings {@code "./"} and {@code ".!"} misses them entirely.
     */
    @Test
    public void trailingDotSegmentsAreNormalized() {
        assertThat(sanitize("foo/.")).isEqualTo("foo");
        assertThat(sanitize("a/b/..")).isEqualTo("a");
        assertThat(sanitize("/a/b/..")).isEqualTo("/a");
        assertThat(sanitize(".")).isEmpty();
        assertThat(sanitize("..")).isEmpty();
    }

    /** {@code ".."} may not navigate above the top of the path hierarchy. */
    @Test
    public void dotDotCannotEscapeHierarchyRoot() {
        assertThat(sanitize("foo/..")).isEmpty();
        assertThat(sanitize("a/../../b")).isEqualTo("b");
    }

    /**
     * A '!' that is not followed by '/' is a filename character rather than a nested jar separator, so normalizing
     * a path that contains one must not insert a '/' after it -- that names an entry that does not exist.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    // #903
    @Test
    public void aPlingWithinAnEntryNameIsNotASeparator(@TempDir final Path tempDir) throws IOException {
        // A '!' only counts as a separator if the path before it names a file, so the jar has to exist on disk
        final String outerJarPath = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(sanitize(outerJarPath + "!/dir!name/./x.txt")).isEqualTo(outerJarPath + "!/dir!name/x.txt");
    }

    /** A nested jar separator {@code '!'} starts a new hierarchy root, which {@code ".."} may not escape. */
    @Test
    public void dotDotCannotEscapePrecedingNestedJarSeparator() {
        assertThat(sanitize("x.jar!/a/..")).isEqualTo("x.jar!");
        assertThat(sanitize("x.jar!/a/b/..")).isEqualTo("x.jar!/a");
    }

    /**
     * More {@code ".."} segments than there are preceding segments to consume must not escape the top of the
     * hierarchy: the excess is simply discarded, and no {@code ".."} is ever emitted into the result.
     */
    @Test
    public void excessDotDotSegmentsCannotEscapeRoot() {
        assertThat(sanitize("../..")).isEmpty();
        assertThat(sanitize("../../../../..")).isEmpty();
        assertThat(sanitize("a/b/../../../../c")).isEqualTo("c");
        assertThat(sanitize("a/../../../../../etc/passwd")).isEqualTo("etc/passwd");
        assertThat(sanitize("a/./../b/../../../c")).isEqualTo("c");
        assertThat(sanitize("x.jar!/a/../../../etc/passwd")).isEqualTo("etc/passwd");
    }

    /**
     * A path that normalizes away to nothing but slashes must not throw when both the initial and the final slash
     * are to be removed -- the leading-slash index must not be left pointing past the truncated buffer.
     */
    @Test
    public void pathNormalizingToRootDoesNotThrowWhenStrippingBothSlashes() {
        assertThat(FileUtils.sanitizeEntryPath("/..", true, true)).isEmpty();
        assertThat(FileUtils.sanitizeEntryPath("/.", true, true)).isEmpty();
        assertThat(FileUtils.sanitizeEntryPath("/../..", true, true)).isEmpty();
        assertThat(FileUtils.sanitizeEntryPath("//..", true, true)).isEmpty();
        assertThat(FileUtils.sanitizeEntryPath("/a/..", true, true)).isEmpty();
    }

    /** Empty segments ({@code "//"}) are collapsed. */
    @Test
    public void emptySegmentsAreCollapsed() {
        assertThat(sanitize("a//b")).isEqualTo("a/b");
    }

    /**
     * A {@code '.'} that does not form a complete segment is an ordinary filename character, so paths containing one
     * must be returned unchanged -- in particular, a trailing slash must be preserved, since normalizing would drop
     * it along with the empty final segment.
     */
    @Test
    public void dotsWithinSegmentNamesAreNotNormalized() {
        assertThat(sanitize("a./b")).isEqualTo("a./b");
        assertThat(sanitize("a./b/")).isEqualTo("a./b/");
        assertThat(sanitize("foo.bar.")).isEqualTo("foo.bar.");
        assertThat(sanitize("a/b/")).isEqualTo("a/b/");
    }
}
