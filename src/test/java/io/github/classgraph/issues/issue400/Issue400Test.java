package io.github.classgraph.issues.issue400;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

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
     * Scan a jarfile whose {@code BOOT-INF/lib/} directory holds {@link #NUM_NESTED_JARS} nested jarfiles.
     *
     * @param jar
     *            the jar URL.
     * @throws IOException
     *             if the classloader could not be closed.
     */
    private void scansJarWithManyNestedJars(final URL jar) throws IOException {
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { jar });
                ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader)
                        .ignoreParentClassLoaders().enableAllInfo().scan()) {
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
     *             if the classloader could not be closed.
     */
    @Test
    public void scansStoredJarWithManyNestedJars() throws IOException {
        scansJarWithManyNestedJars(Issue400Test.class.getClassLoader().getResource("issue400-nested-stored.jar"));
    }

    /**
     * Test a jarfile whose nested jarfiles are deflated, so that each one has to be inflated before it can be read.
     *
     * @throws IOException
     *             if the classloader could not be closed.
     */
    @Test
    public void scansDeflatedJarWithManyNestedJars() throws IOException {
        scansJarWithManyNestedJars(Issue400Test.class.getClassLoader().getResource("issue400-nested-deflated.jar"));
    }
}
