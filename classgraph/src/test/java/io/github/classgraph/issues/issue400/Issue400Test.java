package io.github.classgraph.issues.issue400;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Verify that a jarfile with a large number of stored or deflated nested jarfiles can be scanned, with every nested
 * jarfile opened.
 *
 * @author Róbert Papp ( https://github.com/TWiStErRob )
 */
public class Issue400Test {
    /** The number of jarfiles nested in each of the test jarfiles. */
    private static final int NUM_NESTED_JARS = 128;

    /**
     * Scan a jarfile together with every jarfile nested in its {@code BOOT-INF/lib/} directory. Nested jarfiles in
     * a lib dir are only added to the classpath automatically for a classloader that loads from that lib dir, so
     * they are named explicitly here.
     *
     * @param jarName
     *            the name of the jarfile resource.
     * @throws IOException
     *             if the jarfile could not be read.
     * @throws URISyntaxException
     *             if the resource URL is not a valid URI.
     */
    private static void scansJarWithManyNestedJars(final String jarName) throws IOException, URISyntaxException {
        final var jarPath = Path.of(Issue400Test.class.getClassLoader().getResource(jarName).toURI());
        final List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(jarPath.toString());
        try (var zipFile = new ZipFile(jarPath.toFile())) {
            zipFile.stream().filter(entry -> entry.getName().endsWith(".jar"))
                    .forEach(entry -> classpathEntries.add(jarPath + "!/" + entry.getName()));
        }
        assertThat(classpathEntries).hasSize(1 + NUM_NESTED_JARS);

        try (var scanResult = new ClassGraph().enableClasspathEntries(classpathEntries).enableClassInfo()
                .ignoreClassVisibility().scan()) {
            // There are no classes in any of the jarfiles
            assertThat(scanResult.getAllClassesAsMap()).isEmpty();
            // The outer jarfile and every nested jarfile were opened and are on the classpath
            assertThat(scanResult.getClasspathURLs()).hasSize(1 + NUM_NESTED_JARS);
        }
    }

    /**
     * Test a jarfile whose nested jarfiles are stored.
     *
     * @throws IOException
     *             if the jarfile could not be read.
     * @throws URISyntaxException
     *             if the resource URL is not a valid URI.
     */
    @Test
    public void scansStoredJarWithManyNestedJars() throws IOException, URISyntaxException {
        scansJarWithManyNestedJars("issue400-nested-stored.jar");
    }

    /**
     * Test a jarfile whose nested jarfiles are deflated, so that each one has to be inflated before it can be read.
     *
     * @throws IOException
     *             if the jarfile could not be read.
     * @throws URISyntaxException
     *             if the resource URL is not a valid URI.
     */
    @Test
    public void scansDeflatedJarWithManyNestedJars() throws IOException, URISyntaxException {
        scansJarWithManyNestedJars("issue400-nested-deflated.jar");
    }
}
