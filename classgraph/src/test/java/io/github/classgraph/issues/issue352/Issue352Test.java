package io.github.classgraph.issues.issue352;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.ops4j.pax.url.mvn.MavenResolvers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.issues.issue107.Issue107Test;

/**
 * Unit test.
 */
public class Issue352Test {

    /**
     * Test *.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Test
    public void test() throws IOException {
        final var resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("com.sun.istack",
                "istack-commons-runtime", null, null, "3.0.7");
        assertThat(resolvedFile).isFile();

        // Test that module-info.class is not included in resource list if the root package ("") is not accepted
        try (var scanResult = new ClassGraph().overrideClasspath(resolvedFile).acceptPackagesNonRecursive("")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains("module-info.class");
        }
        try (var scanResult = new ClassGraph().overrideClasspath(resolvedFile).acceptPackages("com.sun.istack")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).doesNotContain("module-info.class");
        }

        // Test that package-info.class is only included in resource list for accepted packages
        final var pkgInfoPath = Issue107Test.class.getPackage().getName().replace('.', '/') + "/package-info.class";
        try (var scanResult = new ClassGraph().acceptPackages(Issue107Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains(pkgInfoPath);
        }
        try (var scanResult = new ClassGraph().acceptPackages(Issue352Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).doesNotContain(pkgInfoPath);
        }
    }
}
