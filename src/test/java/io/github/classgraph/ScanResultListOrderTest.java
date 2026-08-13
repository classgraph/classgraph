package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Every list returned by the API is sorted by name, including the lists of packages and modules found by a scan,
 * which used to be returned in the arbitrary iteration order of the hashmap they are stored in.
 */
public class ScanResultListOrderTest {
    /** The packages found by a scan are listed in name order. */
    @Test
    public void packagesAreListedInNameOrder() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .acceptPackages("io.github.classgraph.issues.issue940").scan()) {
            assertThat(scanResult.getPackageInfo().getNames()).contains("io.github.classgraph.issues.issue940",
                    "io.github.classgraph.issues.issue940.api.base.schema",
                    "io.github.classgraph.issues.issue940.schema").isSorted();
        }
    }

    /** The modules found by a scan are listed in name order. */
    @Test
    public void modulesAreListedInNameOrder() {
        try (ScanResult scanResult = new ClassGraph().enableSystemJarsAndModules().enableClassInfo()
                .acceptPackagesNonRecursive("javax.xml.parsers", "java.sql", "java.util.function").scan()) {
            // On Java 9+ these are the system modules; on Java 8 they are the jars on the classpath that carry an
            // Automatic-Module-Name manifest entry, so there is a list to sort either way
            assertThat(scanResult.getModuleInfo().getNames()).isSorted();
        }
    }
}
