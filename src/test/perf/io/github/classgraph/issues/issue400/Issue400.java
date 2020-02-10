package io.github.classgraph.issues.issue400;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Verify that a large number of stored/deflated nested JAR entries don't cause problems.
 */
public class Issue400 {

    private static final long MB = 1024 * 1024;
    private static final long MEMORY_TOLERANCE = 2 * MB;

    @Test
    public void loadsStoredJarWithManyNestedEntriesAndDoesNotUseMuchMemory() {
        loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(
                Issue400.class.getClassLoader().getResource("issue400-nested-stored.jar"));
    }

    @Test
    public void loadsDeflatedJarWithManyNestedEntriesAndDoesNotUseMuchMemory() {
        loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(
                Issue400.class.getClassLoader().getResource("issue400-nested-deflated.jar"));
    }

    private void loadsJarWithManyNestedEntriesAndDoesNotUseMuchMemory(URL... jars) {
        long ramAtStart = usedRam();
        long ramAfterScan;
        try (
                ScanResult scanResult = new ClassGraph()
                        .overrideClassLoaders(new URLClassLoader(jars))
                        .enableAllInfo()
                        .scan()
        ) {
            ramAfterScan = usedRam();
            // There are no classes in any of the JARs.
            assertThat(scanResult.getAllClassesAsMap()).isEmpty();
            // Check if it contains the JAR and all nested entries.
            assertThat(scanResult.getClasspathURLs()).hasSize(1 + 128);
        }
        long ramAtEnd = usedRam();

        assertThat(ramAfterScan)
                .withFailMessage("Memory usage while using ScanResult should stay within reasonable range: "
                        + "went from %s to %s MB.", ramAtStart / MB, ramAfterScan / MB)
                .isGreaterThanOrEqualTo(ramAtStart)
                .isCloseTo(ramAtStart, offset(MEMORY_TOLERANCE));

        assertThat(ramAtStart)
                .withFailMessage("Memory usage after cleaning up should stay within reasonable range: "
                        + "went from %s to %s MB.", ramAtStart / MB, ramAtEnd / MB)
                .isCloseTo(ramAtEnd, offset(MEMORY_TOLERANCE));
    }

    /**
     * @return used JVM heap size allocated in RAM
     * @see <a href="https://stackoverflow.com/a/42567450/253468">What are Runtime.getRuntime().totalMemory() and freeMemory()?</a>
     */
    private long usedRam() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        System.runFinalization();
        runtime.gc();
        System.runFinalization();
        runtime.gc();
        System.runFinalization();
        return (runtime.totalMemory() - runtime.freeMemory());
    }
}
