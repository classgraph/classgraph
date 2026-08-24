package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.features.CustomURLScheme;

/**
 * A scan reads whatever the classpath names, so the URL schemes that fetch over a network are denied to begin with,
 * and every other scheme -- including one an application registered a handler for -- is read as found.
 */
class URLSchemeDenyTest {
    /** Register the URL stream handler for the custom scheme. */
    @BeforeAll
    static void setup() {
        CustomURLScheme.register();
    }

    /**
     * Build the URL of a jarfile on the test classpath, under the custom scheme.
     *
     * @return the URL.
     * @throws MalformedURLException
     *             if the URL could not be built.
     */
    private static URL customSchemeJarURL() throws MalformedURLException {
        final var filePath = URLSchemeDenyTest.class.getClassLoader().getResource("nested-jars-level1.zip")
                .getPath();
        return new URL(CustomURLScheme.SCHEME + ":" + filePath);
    }

    /** The schemes that every JVM can fetch over a network are denied by the constructor. */
    @Test
    void theNetworkSchemesAreDeniedByDefault() {
        assertThat(new ClassGraph().enableClasspath().scanSpec.vfsSpec.getDeniedURLSchemes())
                .containsExactlyInAnyOrder("http", "https", "ftp", "mailto");
    }

    /** Enabling a network scheme takes it back off the denied list. */
    @Test
    void enablingANetworkSchemeUndoesTheDefault() {
        assertThat(
                new ClassGraph().enableClasspath().enableURLScheme("https").scanSpec.vfsSpec.getDeniedURLSchemes())
                .containsExactlyInAnyOrder("http", "ftp", "mailto");
        assertThat(
                new ClassGraph().enableClasspath().enableRemoteJarScanning().scanSpec.vfsSpec.getDeniedURLSchemes())
                .containsExactlyInAnyOrder("ftp", "mailto");
    }

    /**
     * A scheme that something has registered a URL stream handler for is scanned without being enabled.
     *
     * @throws MalformedURLException
     *             if the URL could not be built.
     */
    @Test
    void aCustomURLSchemeIsScannedWithoutBeingEnabled() throws MalformedURLException {
        try (var scanResult = new ClassGraph().enableClasspathEntries((Object) customSchemeJarURL()).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
        }
    }

    /**
     * A scheme the caller denied is not fetched, even though the JVM has a handler for it.
     *
     * @throws MalformedURLException
     *             if the URL could not be built.
     */
    @Test
    void aDeniedURLSchemeIsNotFetched() throws MalformedURLException {
        try (var scanResult = new ClassGraph().disableURLScheme(CustomURLScheme.SCHEME)
                .enableClasspathEntries((Object) customSchemeJarURL()).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).isEmpty();
        }
    }
}
