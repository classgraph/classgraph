package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.features.CustomURLScheme;

/**
 * Test that a classpath entry URL whose scheme is not followed by a slash, such as
 * {@code "customscheme:dir/x.jar"}, is fetched through the scheme's URL handler, rather than being read as a file
 * path relative to the current directory.
 */
class URLSchemeNoSlashTest {
    /** Register the URL stream handler for the custom scheme. */
    @BeforeAll
    static void setup() {
        CustomURLScheme.register();
    }

    /**
     * The URL of a jarfile on the test classpath, under the custom scheme, with a path relative to the current
     * directory, so that no slash follows the scheme.
     *
     * @return the URL.
     * @throws Exception
     *             if the jarfile could not be found.
     */
    private static String relativeCustomSchemeURL() throws Exception {
        final var jarPath = Path
                .of(URLSchemeNoSlashTest.class.getClassLoader().getResource("nested-jars-level1.zip").toURI());
        final var relativePath = Path.of("").toAbsolutePath().relativize(jarPath).toString().replace('\\', '/');
        return CustomURLScheme.SCHEME + ":" + relativePath;
    }

    /**
     * Scan a classpath entry, and check that it was fetched through the custom scheme's URL handler.
     *
     * @param classpathEntry
     *            the classpath entry.
     * @param url
     *            the URL that the classpath entry names.
     */
    private static void scanThroughTheURLHandler(final Object classpathEntry, final String url) {
        // Other tests record URLs in remappedURLs too, so remove this one first, to see that this scan opened it
        CustomURLScheme.remappedURLs.remove(url);
        // The scheme is allowed so that a classpath string is not split at the scheme's ':'
        try (var scanResult = new ClassGraph().allowURLScheme(CustomURLScheme.SCHEME)
                .enableClasspathEntries(classpathEntry).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
            assertThat(CustomURLScheme.remappedURLs).containsKey(url);
        }
    }

    /** A classpath entry string. */
    @Test
    void aClasspathEntryStringIsFetchedThroughTheURLHandler() throws Exception {
        final var url = relativeCustomSchemeURL();
        scanThroughTheURLHandler(url, url);
    }

    /** A classpath entry {@link java.net.URL}. */
    @Test
    void aClasspathEntryURLIsFetchedThroughTheURLHandler() throws Exception {
        final var url = relativeCustomSchemeURL();
        scanThroughTheURLHandler(URI.create(url).toURL(), url);
    }
}
