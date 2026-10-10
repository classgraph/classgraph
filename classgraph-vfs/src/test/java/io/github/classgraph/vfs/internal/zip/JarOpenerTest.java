package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/**
 * What {@link JarOpener} does with a jarfile it has opened but cannot hand over.
 */
public class JarOpenerTest {
    /**
     * Build a jarfile holding a single entry.
     *
     * @return the bytes of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] jarBytes() throws IOException {
        final var bytesOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(bytesOut)) {
            zipOut.putNextEntry(new ZipEntry("testpkg/test.txt"));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return bytesOut.toByteArray();
    }

    /**
     * A jarfile opened for a {@link Vfs} that has been closed -- which happens when the {@link Vfs} is closed while
     * another thread is opening the jarfile -- is turned away without its central directory being read, and what
     * was opened to read it is released: here, the temporary file the jarfile was spilled to.
     *
     * @throws Exception
     *             if the jarfile could not be written, or the temporary directory could not be listed.
     */
    @Test
    public void aJarfileOpenedForAClosedVfsIsReleasedUnread() throws Exception {
        final var jarBytes = jarBytes();
        // A name that nothing else uses, since the temporary file the jarfile is spilled to is named after it
        final var name = "closed-vfs-" + UUID.randomUUID() + ".jar";
        final var vfs = new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0));
        vfs.close();

        assertThatThrownBy(() -> JarOpener.openJarFromInputStream(new ByteArrayInputStream(jarBytes),
                jarBytes.length, name, vfs, /* log = */ null)).isInstanceOf(IOException.class)
                .hasMessage("Already closed");
        try (var tempFiles = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(tempFiles.map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(name))).isEmpty();
        }
    }
}
