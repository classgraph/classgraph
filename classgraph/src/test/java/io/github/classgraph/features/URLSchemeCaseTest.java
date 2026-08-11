package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * A URL scheme is case-insensitive, so a classpath element whose scheme is not written in lowercase is scanned by
 * an enabled scheme just the same as one that is.
 */
public class URLSchemeCaseTest {
    /** Register the URL stream handler for the custom scheme. */
    @BeforeAll
    static void setup() {
        new CustomURLScheme();
    }

    /** A classpath element whose URL scheme is written in uppercase is scanned by the enabled scheme. */
    @Test
    public void anUppercaseURLSchemeIsStillTheEnabledScheme() {
        final var filePath = getClass().getClassLoader().getResource("nested-jars-level1.zip").getPath();
        final var upperCaseSchemeURL = CustomURLScheme.SCHEME.toUpperCase(Locale.ROOT) + ":" + filePath;

        try (var scanResult = new ClassGraph().enableURLScheme(CustomURLScheme.SCHEME)
                .overrideClasspath(upperCaseSchemeURL).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
        }
    }
}
