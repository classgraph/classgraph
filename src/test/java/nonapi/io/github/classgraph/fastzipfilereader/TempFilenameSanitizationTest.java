package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a temporary file can be created for a nested jar whose entry name contains characters that are legal
 * in a zip entry name but not in a filename. A nested jar that is too large to be extracted to RAM is spilled to a
 * temporary file named after the zip entry it came from, so every such character has to be replaced first.
 */
public class TempFilenameSanitizationTest {
    /**
     * Every ASCII character that Windows rejects in a filename (the control characters, and {@code " * / < > ? \
     * |}), plus {@code :}, which Windows accepts but treats as the start of an NTFS alternate data stream. Linux
     * and macOS reject only {@code /}. (Measured on real GitHub Actions runners for all three platforms.)
     */
    private static final String UNSAFE_CHARS = "\b\t\n\f\r\"*/:<>?\\|";

    /** A nested jar whose entry name is not a valid filename must still get a temporary file. */
    @Test
    public void unsafeCharactersInEntryNameAreReplaced() throws Exception {
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final File tempFile = nestedJarHandler.makeTempFile("BOOT-INF/lib/na" + UNSAFE_CHARS + "me.jar",
                    /* onlyUseLeafname = */ false);
            assertThat(tempFile).exists();
            for (int i = 0; i < UNSAFE_CHARS.length(); i++) {
                assertThat(tempFile.getName()).doesNotContain(UNSAFE_CHARS.substring(i, i + 1));
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }
}
