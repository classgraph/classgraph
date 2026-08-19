package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A URL scheme is case-insensitive, so a denied scheme matches a path that spells the scheme in any case. */
public class URLSchemeCaseTest {
    /** The scheme of the test URL stream handler. */
    private static final String SCHEME = "vfstest";

    /** The content of the test resource. */
    private static final String RESOURCE_CONTENT = "url-scheme-case-test";

    static {
        // Map "vfstest:<path>" to "file:<path>", so that a jarfile can be downloaded from a URL without a network
        URL.setURLStreamHandlerFactory(protocol -> SCHEME.equals(protocol) ? new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL url) throws IOException {
                return new URL("file:" + url.getPath()).openConnection();
            }
        } : null);
    }

    /**
     * Write a jarfile containing a single entry.
     *
     * @param jarFile
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("com/xyz/widget.txt"));
            zipOut.write(RESOURCE_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * A scheme that something has registered a URL stream handler for is opened without having to be enabled,
     * whatever case the path spells it in.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aCustomURLSchemeIsOpenedWithoutBeingEnabled(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var url = SCHEME.toUpperCase(Locale.ROOT) + ":" + jarFile.getPath();
            final var entry = Objects.requireNonNull(vfs.open(url).getEntry("com/xyz/widget.txt"));
            assertThat(new String(entry.load(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /**
     * A denied URL scheme still matches when the path spells the scheme in uppercase.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void anUppercaseURLSchemeIsStillTheDeniedScheme(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile);

        try (var vfs = new Vfs(new VfsSpec().disableURLScheme(SCHEME))) {
            final var url = SCHEME.toUpperCase(Locale.ROOT) + ":" + jarFile.getPath();
            assertThatThrownBy(() -> vfs.open(url)).isInstanceOf(IOException.class)
                    .hasMessageContaining("is not allowed");
        }
    }
}
