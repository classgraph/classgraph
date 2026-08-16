package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

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

/** A URL scheme is case-insensitive, so an enabled scheme matches a path that spells the scheme in any case. */
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

    /** An enabled URL scheme still matches when the path spells the scheme in uppercase. */
    @Test
    public void anUppercaseURLSchemeIsStillTheEnabledScheme(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("com/xyz/widget.txt"));
            zipOut.write(RESOURCE_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try (var vfs = new Vfs(new VfsSpec().enableURLScheme(SCHEME))) {
            final var url = SCHEME.toUpperCase(Locale.ROOT) + ":" + jarFile.getPath();
            final var entry = Objects.requireNonNull(vfs.open(url).getEntry("com/xyz/widget.txt"));
            assertThat(new String(entry.load(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }
}
