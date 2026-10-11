package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Jimfs;

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
                new ClassGraph().enableClasspath().allowURLScheme("https").scanSpec.vfsSpec.getDeniedURLSchemes())
                .containsExactlyInAnyOrder("http", "ftp", "mailto");
        assertThat(
                new ClassGraph().enableClasspath().enableRemoteJarScanning().scanSpec.vfsSpec.getDeniedURLSchemes())
                .containsExactlyInAnyOrder("ftp", "mailto");
    }

    /**
     * A scheme that something has registered a URL stream handler for is scanned without being allowed.
     *
     * @throws MalformedURLException
     *             if the URL could not be built.
     */
    @Test
    void aCustomURLSchemeIsScannedWithoutBeingAllowed() throws MalformedURLException {
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
        try (var scanResult = new ClassGraph().denyURLScheme(CustomURLScheme.SCHEME)
                .enableClasspathEntries((Object) customSchemeJarURL()).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).isEmpty();
        }
    }

    /**
     * Copy a resource of the test classpath into a filesystem.
     *
     * @param resourcePath
     *            the path of the resource.
     * @param target
     *            the path to copy it to.
     * @return the target path.
     * @throws IOException
     *             if the resource could not be copied.
     * @throws URISyntaxException
     *             if the resource's URL is not a valid URI.
     */
    private static Path copyResource(final String resourcePath, final Path target)
            throws IOException, URISyntaxException {
        Files.createDirectories(target.getParent());
        return Files.copy(Path.of(URLSchemeDenyTest.class.getClassLoader().getResource(resourcePath).toURI()),
                target);
    }

    /**
     * Scan one classpath entry for one class.
     *
     * @param classpathEntry
     *            the classpath entry.
     * @param className
     *            the name of the class.
     * @param denyJimfs
     *            whether to deny the "jimfs" scheme.
     * @return whether the class was found.
     */
    private static boolean classIsFound(final Object classpathEntry, final String className,
            final boolean denyJimfs) {
        final var classGraph = new ClassGraph().enableClasspathEntries(classpathEntry).enableClassInfo()
                .ignoreClassVisibility();
        if (denyJimfs) {
            classGraph.denyURLScheme("jimfs");
        }
        try (var scanResult = classGraph.scan()) {
            return scanResult.getClassInfo(className) != null;
        }
    }

    /**
     * A jarfile or a directory that an installed filesystem provider serves under a denied scheme is not read when
     * it is named by a URL or a URI, just as it is not read when no filesystem provider serves the scheme.
     *
     * @throws IOException
     *             if the test files could not be written.
     * @throws URISyntaxException
     *             if a resource's URL is not a valid URI.
     */
    @Test
    void aDeniedSchemeThatAFilesystemProviderServesIsNotRead() throws IOException, URISyntaxException {
        try (FileSystem memFs = Jimfs.newFileSystem()) {
            final var jarURI = copyResource("multi-release-jar.jar", memFs.getPath("/lib/multi-release-jar.jar"))
                    .toUri();
            final var classFile = "io/github/classgraph/issues/issue146/CompiledWithJDK8.class";
            copyResource(classFile, memFs.getPath("/classes/" + classFile));
            final var dirURI = memFs.getPath("/classes").toUri();
            final var dirClassName = "io.github.classgraph.issues.issue146.CompiledWithJDK8";
            for (final Object jarEntry : new Object[] { jarURI, jarURI.toURL(), jarURI.toString() }) {
                assertThat(classIsFound(jarEntry, "mrj.Cls", false)).as("allowed: %s", jarEntry).isTrue();
                assertThat(classIsFound(jarEntry, "mrj.Cls", true)).as("denied: %s", jarEntry).isFalse();
            }
            for (final Object dirEntry : new Object[] { dirURI, dirURI.toURL(), dirURI.toString() }) {
                assertThat(classIsFound(dirEntry, dirClassName, false)).as("allowed: %s", dirEntry).isTrue();
                assertThat(classIsFound(dirEntry, dirClassName, true)).as("denied: %s", dirEntry).isFalse();
            }
        }
    }
}
