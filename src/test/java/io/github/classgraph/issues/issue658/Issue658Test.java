package io.github.classgraph.issues.issue658;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Rejecting one system module used to switch off system module scanning entirely, so that no system module was
 * scanned at all (#658).
 *
 * <p>
 * Skipped on JDK 8, which has no modules.
 */
public class Issue658Test {
    /** A class in the {@code jdk.compiler} system module. */
    private static final String TREE = "com.sun.source.tree.Tree";

    /** A class in the {@code java.base} system module. */
    private static final String ARRAY_LIST = "java.util.ArrayList";

    /** Rejecting one system module still leaves the other system modules scannable. */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void rejectingASystemModuleDoesNotDisableTheRest() {
        try (ScanResult scanResult = new ClassGraph().enableSystemJarsAndModules()
                .rejectModules("jdk.compiler").acceptPackages("java.util", "com.sun.source.tree").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(ARRAY_LIST).doesNotContain(TREE);
        }
    }
}
