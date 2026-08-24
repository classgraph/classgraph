package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.features.CustomURLScheme;

/**
 * Test that a URL scheme containing a digit, which RFC 3986 allows anywhere after the first character, is
 * recognized as a scheme when it appears in an overridden classpath entry string.
 */
class URLSchemeDigitTest {
    /** A classpath entry string whose URL scheme contains a digit is fetched through the URL handler. */
    @Test
    void aSchemeWithADigitIsRecognizedInAClasspathEntryString() {
        CustomURLScheme.register();
        final var filePath = URLSchemeDigitTest.class.getClassLoader().getResource("nested-jars-level1.zip")
                .getPath();
        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(CustomURLScheme.SCHEME_WITH_DIGIT + ":" + filePath).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
        }
    }
}
