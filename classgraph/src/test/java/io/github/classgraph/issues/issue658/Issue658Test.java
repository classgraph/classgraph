package io.github.classgraph.issues.issue658;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/** Test the accept/reject criteria for system modules. */
public class Issue658Test {
    /** A class in the {@code jdk.compiler} system module. */
    private static final String TREE = "com.sun.source.tree.Tree";

    /** A class in the {@code java.base} system module. */
    private static final String ARRAY_LIST = "java.util.ArrayList";

    /** A module that is accepted by name is scanned, even if it is a system module. */
    @Test
    public void acceptedSystemModuleIsScannedWithoutEnablingSystemModules() {
        try (var scanResult = new ClassGraph().enableClassInfo().acceptModules("jdk.compiler")
                .acceptPackages("com.sun.source.tree").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(TREE);
        }
    }

    /** Accepting a module by name does not cause any other system module to be scanned. */
    @Test
    public void acceptingASystemModuleDoesNotScanTheOthers() {
        try (var scanResult = new ClassGraph().enableClassInfo().acceptModules("jdk.compiler").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(TREE).doesNotContain(ARRAY_LIST);
        }
    }

    /** Rejecting one system module still leaves the other system modules scannable. */
    @Test
    public void rejectingASystemModuleDoesNotDisableTheRest() {
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules().rejectModules("jdk.compiler")
                .acceptPackages("java.util", "com.sun.source.tree").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(ARRAY_LIST).doesNotContain(TREE);
        }
    }
}
