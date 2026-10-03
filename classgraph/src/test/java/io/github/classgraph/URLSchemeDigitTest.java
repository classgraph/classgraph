package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.github.classgraph.features.CustomURLScheme;

/**
 * Test that a URL scheme containing a digit, which RFC 3986 allows anywhere after the first character, is
 * recognized as a scheme when it appears in an overridden classpath entry string.
 */
class URLSchemeDigitTest {
    /**
     * Scan a jarfile through a URL with a custom scheme, and check that it was read through the scheme's URL
     * handler, and that the URIs it is reported at name it by that URL.
     *
     * @param url
     *            the URL of the jarfile.
     * @throws Exception
     *             if the URL handler could not open a reported URI.
     */
    private static void scanThroughTheURLHandler(final String url) throws Exception {
        // The URL handler records each URL it opens in the spelling URL#toString() gives it, which drops an empty
        // authority. Other tests record URLs there too, so remove this one first, to see that this scan opened it
        final var handlerKey = new URI(url).toURL().toString();
        CustomURLScheme.remappedURLs.remove(handlerKey);
        // The scheme is allowed so that its ':' is not read as a path separator, which would split the URL in
        // two and leave the bare path of the jarfile to be scanned as a file, never calling the URL handler
        try (var scanResult = new ClassGraph().allowURLScheme(CustomURLScheme.SCHEME_WITH_DIGIT)
                .enableClasspathEntries(url).scan()) {
            assertThat(CustomURLScheme.remappedURLs).containsKey(handlerKey);
            // (URI#equals treats an empty authority as no authority, so "x:///a" equals "x:/a")
            assertThat(scanResult.getClasspathURIs()).containsExactly(new URI(url));
            final var resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).containsExactly("level2.jar");
            final var resourceURI = resources.get(0).getURI();
            assertThat(resourceURI)
                    .isEqualTo(new URI("jar:" + scanResult.getClasspathURIs().get(0) + "!/level2.jar"));
            // The URI names the resource that was scanned
            try (var in = resourceURI.toURL().openStream()) {
                assertThat(in.readAllBytes()).isEqualTo(resources.get(0).load());
            }
        }
    }

    /** A classpath entry string whose URL scheme contains a digit is fetched through the URL handler. */
    @Test
    void aSchemeWithADigitIsRecognizedInAClasspathEntryString() throws Exception {
        CustomURLScheme.register();
        final var filePath = URLSchemeDigitTest.class.getClassLoader().getResource("nested-jars-level1.zip")
                .getPath();
        scanThroughTheURLHandler(CustomURLScheme.SCHEME_WITH_DIGIT + ":" + filePath);
    }

    /**
     * A URL with an empty authority keeps the slash that begins its path, so the first directory of the path is not
     * read as the authority.
     */
    @Test
    void aUrlWithAnEmptyAuthorityIsFetchedThroughTheURLHandler() throws Exception {
        CustomURLScheme.register();
        final var filePath = URLSchemeDigitTest.class.getClassLoader().getResource("nested-jars-level1.zip")
                .getPath();
        scanThroughTheURLHandler(CustomURLScheme.SCHEME_WITH_DIGIT + "://" + filePath);
    }
}
