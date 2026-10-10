package io.github.classgraph.base.internal.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PathSyntax#sanitizeEntryPath(String, boolean, boolean)} and
 * {@link PathSyntax#sanitizeRelativePath(String, boolean)}: {@code "."} and {@code ".."} segments and empty
 * segments must be normalized away, wherever they appear in the path -- including as the final segment, where they
 * are not followed by a separator.
 */
public class PathSyntaxSanitizeEntryPathTest {
    /**
     * Sanitize a path, without stripping leading or trailing slashes, so that the normalization itself is what is
     * under test.
     *
     * @param path
     *            the path to sanitize
     * @return the sanitized path
     */
    private static String sanitize(final String path) {
        return PathSyntax.sanitizeEntryPath(path, /* removeInitialSlash = */ false, /* removeFinalSlash = */ false);
    }

    /**
     * A {@code "."} or {@code ".."} segment in the middle of a path is normalized away.
     */
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
     * A nested jar separator {@code '!'} starts a new hierarchy root, which {@code ".."} may not escape. A '!' is
     * only a separator if the path before it names an existing file (#903), so the jarfile has to exist.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into
     * @throws IOException
     *             if the jarfile could not be written
     */
    @Test
    public void dotDotCannotEscapePrecedingNestedJarSeparator(@TempDir final Path tempDir) throws IOException {
        final var jar = Files.write(tempDir.resolve("x.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(sanitize(jar + "!/a/b/..")).isEqualTo(jar + "!/a");
        assertThat(sanitize(jar + "!/a/../../../etc/passwd")).isEqualTo(jar + "!/etc/passwd");
        // A section left empty is dropped together with the '!' before it; the result still names the jarfile
        assertThat(sanitize(jar + "!/a/..")).isEqualTo(jar);
    }

    /**
     * A '!' after a path that names no file is an ordinary filename character (#903), so {@code ".."} after it
     * collapses the segment it is part of like any other.
     */
    @Test
    public void aPlingAfterAPathThatIsNotAFileIsAnOrdinaryCharacter() {
        final var jar = "no-such-file-" + UUID.randomUUID() + ".jar";
        assertThat(sanitize(jar + "!/a/..")).isEqualTo(jar + "!");
        assertThat(sanitize(jar + "!/a/b/..")).isEqualTo(jar + "!/a");
        assertThat(sanitize(jar + "!/a/../../../etc/passwd")).isEqualTo("etc/passwd");
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
    }

    /**
     * A path that normalizes away to nothing but slashes must not throw when both the initial and the final slash
     * are to be removed -- the leading-slash index must not be left pointing past the truncated buffer.
     */
    @Test
    public void pathNormalizingToRootDoesNotThrowWhenStrippingBothSlashes() {
        assertThat(PathSyntax.sanitizeEntryPath("/..", true, true)).isEmpty();
        assertThat(PathSyntax.sanitizeEntryPath("/.", true, true)).isEmpty();
        assertThat(PathSyntax.sanitizeEntryPath("/../..", true, true)).isEmpty();
        assertThat(PathSyntax.sanitizeEntryPath("//..", true, true)).isEmpty();
        assertThat(PathSyntax.sanitizeEntryPath("/a/..", true, true)).isEmpty();
    }

    /** Empty segments ({@code "//"}) are collapsed. */
    @Test
    public void emptySegmentsAreCollapsed() {
        assertThat(sanitize("a//b")).isEqualTo("a/b");
    }

    /**
     * A {@code '.'} that does not form a complete segment is an ordinary filename character, so paths containing
     * one must be returned unchanged -- in particular, a trailing slash must be preserved, since normalizing would
     * drop it along with the empty final segment.
     */
    @Test
    public void dotsWithinSegmentNamesAreNotNormalized() {
        assertThat(sanitize("a./b")).isEqualTo("a./b");
        assertThat(sanitize("a./b/")).isEqualTo("a./b/");
        assertThat(sanitize("foo.bar.")).isEqualTo("foo.bar.");
        assertThat(sanitize("a/b/")).isEqualTo("a/b/");
    }

    /**
     * A path within a root is sanitized like any other path: {@code "."} and empty segments are dropped,
     * {@code ".."} cannot climb above the root, and the initial {@code '/'} is removed.
     */
    @Test
    public void relativePathIsSanitized() {
        assertThat(PathSyntax.sanitizeRelativePath("/a/./b//c/../d", false)).isEqualTo("a/b/d");
        assertThat(PathSyntax.sanitizeRelativePath("../../etc/passwd", false)).isEqualTo("etc/passwd");
        assertThat(PathSyntax.sanitizeRelativePath("a/b/", false)).isEqualTo("a/b/");
        assertThat(PathSyntax.sanitizeRelativePath("a/b/", true)).isEqualTo("a/b");
        assertThat(PathSyntax.sanitizeRelativePath("", false)).isEmpty();
    }

    /**
     * Within a root, every '!' is an ordinary character, whatever the path before it would name on the host, since
     * a path within a root is not a host path.
     *
     * @param tempDir
     *            a temporary directory holding a file that the part of the path before the '!' names
     * @throws IOException
     *             if the file could not be written
     */
    @Test
    public void aPlingInARelativePathIsAnOrdinaryCharacter(@TempDir final Path tempDir) throws IOException {
        assertThat(PathSyntax.sanitizeRelativePath("dir!name/./x.txt", false)).isEqualTo("dir!name/x.txt");
        // Shaped like a URL, which a host path would read the first '!' of as a separator
        assertThat(PathSyntax.sanitizeRelativePath("ab:c!d/./e.txt", false)).isEqualTo("ab:c!d/e.txt");
        // The part before the '!' names an existing file, which a host path would read the '!' after as a separator
        final var file = Files.write(tempDir.resolve("f"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax.sanitizeRelativePath(file + "!a/./b.txt", false))
                .isEqualTo(file.replaceFirst("^/+", "") + "!a/b.txt");
        assertThat(PathSyntax.sanitizeRelativePath(file + "!a/../b.txt", false))
                .isEqualTo(file.substring(0, file.lastIndexOf('/') + 1).replaceFirst("^/+", "") + "b.txt");
    }
}
