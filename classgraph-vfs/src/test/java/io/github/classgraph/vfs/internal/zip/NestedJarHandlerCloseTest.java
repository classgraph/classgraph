package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.VfsSession;

/**
 * The caches held by a {@link NestedJarHandler} are dropped as the first step of tearing down the session backing
 * them, so a lookup that got through after that would open a fresh zipfile that nothing would ever close again.
 * Each cache holds the session's own closed flag, so it turns the lookup away itself.
 */
public class NestedJarHandlerCloseTest {
    /**
     * Write a jarfile holding a single entry.
     *
     * @param jarFile
     *            the jarfile to write
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jarFile) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("testpkg/test.txt"));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * A jarfile that was already opened can be opened again through the cache, but once the session is closed, the
     * cache turns the lookup away rather than handing back a zipfile whose storage has been released, or opening
     * one that the teardown has already gone past.
     *
     * @param tempDir
     *            a directory to write the jarfile into
     * @throws Exception
     *             if the jarfile could not be written or opened
     */
    @Test
    public void aClosedSessionTurnsAwayALookupInTheZipfileCaches(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "closed-session.jar");
        writeJar(jarFile);

        final var session = new VfsSession(new VfsSpec(), new InterruptionChecker());
        final var nestedJarHandler = new NestedJarHandler(session);
        try {
            final var map = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap();
            assertThat(map.get(jarFile.getPath(), /* log = */ null).getKey()).isNotNull();

            session.close(/* log = */ null);

            assertThatThrownBy(() -> map.get(jarFile.getPath(), /* log = */ null)).isInstanceOf(IOException.class)
                    .hasMessage("Already closed");
            // A path that was never opened is turned away by the same check, before anything is opened for it
            assertThatThrownBy(() -> map.get(new File(tempDir, "never-opened.jar").getPath(), /* log = */ null))
                    .isInstanceOf(IOException.class).hasMessage("Already closed");
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            session.close(/* log = */ null);
        }
    }
}
