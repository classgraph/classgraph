package io.github.classgraph.issues.issue400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Verify that a large number of stored/deflated nested JAR entries don't cause memory problems.
 * 
 * @author Róbert Papp ( https://github.com/TWiStErRob )
 */
public class Issue400 {

    private static final long MB = 1024 * 1024;
    private static final long MEMORY_TOLERANCE = 4 * MB;

    /**
     * @return used JVM heap size allocated in RAM
     * @see <a href="https://stackoverflow.com/a/42567450/253468">What are Runtime.getRuntime().totalMemory() and
     *      freeMemory()?</a>
     */
    private long usedRam() {
        final Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        System.runFinalization();
        runtime.gc();
        System.runFinalization();
        runtime.gc();
        System.runFinalization();
        return (runtime.totalMemory() - runtime.freeMemory());
    }

    /**
     * Test whether RAM leaks, or whether nested deflated jars cause large RAM overhead.
     * 
     * @param jars
     *            the jar URLs.
     */
    @SuppressWarnings("null")
    private void loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(final URL... jars) {
        final long ramAtStart = usedRam();
        long ramAfterScan;
        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(jars))
                .ignoreParentClassLoaders().enableAllInfo().scan()) {
            ramAfterScan = usedRam();
            // There are no classes in any of the JARs.
            assertThat(scanResult.getAllClassesAsMap()).isEmpty();
            // Check if it contains the JAR and all nested entries.
            assertThat(scanResult.getClasspathURLs()).hasSize(1 + 128);
        }
        final long ramAtEnd = usedRam();

        assertThat(ramAtStart)
                .withFailMessage("Memory usage while using ScanResult should stay within reasonable range: "
                        + "went from %s to %s MB.", ramAtStart / MB, ramAfterScan / MB)
                .isCloseTo(ramAfterScan, offset(MEMORY_TOLERANCE));

        assertThat(ramAtStart)
                .withFailMessage("Memory usage after cleaning up should stay within reasonable range: "
                        + "went from %s to %s MB.", ramAtStart / MB, ramAtEnd / MB)
                .isCloseTo(ramAtEnd, offset(MEMORY_TOLERANCE));
    }

    /**
     * Test jar with stored entries.
     */
    @Test
    public void loadsStoredJarWithManyNestedEntriesAndDoesNotUseMuchMemory() {
        loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(
                Issue400.class.getClassLoader().getResource("issue400-nested-stored.jar"));
    }

    /**
     * Test jar with deflated entries.
     */
    @Test
    public void loadsDeflatedJarWithManyNestedEntriesAndDoesNotUseMuchMemory() {
        loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(
                Issue400.class.getClassLoader().getResource("issue400-nested-deflated.jar"));
    }
}
