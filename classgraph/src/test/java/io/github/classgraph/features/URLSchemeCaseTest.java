package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * A URL scheme is case-insensitive, so a classpath string whose scheme is not written in lowercase is split at the
 * same points as one that is.
 */
public class URLSchemeCaseTest {
    /** Register the URL stream handler for the custom scheme. */
    @BeforeAll
    static void setup() {
        CustomURLScheme.register();
    }

    /** A classpath string whose URL scheme is written in uppercase is not split at the scheme's colon. */
    @Test
    public void anUppercaseURLSchemeIsStillTheEnabledScheme() {
        final var filePath = getClass().getClassLoader().getResource("nested-jars-level1.zip").getPath();
        final var upperCaseSchemeURL = CustomURLScheme.SCHEME.toUpperCase(Locale.ROOT) + ":" + filePath;

        // The scheme has to be enabled for a classpath *string*: a classpath string is split on ':' on Unix, so
        // without this the string would be split at the scheme's own colon. (The scheme does not have to be
        // enabled to be fetched from -- only http, https, ftp and mailto do)
        try (var scanResult = new ClassGraph().enableURLScheme(CustomURLScheme.SCHEME)
                .enableClasspathEntries(upperCaseSchemeURL).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
        }
    }
}
