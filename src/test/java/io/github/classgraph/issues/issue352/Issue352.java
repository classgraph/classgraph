package io.github.classgraph.issues.issue352;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.ops4j.pax.url.mvn.MavenResolvers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.issues.issue107.Issue107Test;

/**
 * Unit test.
 */
public class Issue352 {
    /** Test **/
    @Test
    public void test() throws IOException {
        final File resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("com.sun.istack",
                "istack-commons-runtime", null, null, "3.0.7");
        assertThat(resolvedFile).isFile();

        // Test that module-info.class is not included in resource list if the root package ("") is not whitelisted
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(resolvedFile)
                .whitelistPackagesNonRecursive("").enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains("module-info.class");
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(resolvedFile)
                .whitelistPackages("com.sun.istack").enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).doesNotContain("module-info.class");
        }

        // Test that package-info.class is only included in resource list for whitelisted packages 
        final String pkgInfoPath = Issue107Test.class.getPackage().getName().replace('.', '/')
                + "/package-info.class";
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue107Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains(pkgInfoPath);
        }
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue352.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).doesNotContain(pkgInfoPath);
        }
    }
}
