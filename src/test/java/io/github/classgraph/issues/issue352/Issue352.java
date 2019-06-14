package io.github.classgraph.issues.issue352;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Test;
import org.ops4j.pax.url.mvn.MavenResolvers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue352 {
    /** Test that module-info.class is not included in results. */
    @Test
    public void test() throws IOException {
        final File resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("com.sun.istack",
                "istack-commons-runtime", null, null, "3.0.7");
        assertThat(resolvedFile).isFile();

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(resolvedFile)
                .whitelistPackagesNonRecursive("").enableClassInfo().scan()) {
            final List<String> classNames = scanResult.getAllClasses().getNames();
            assertThat(classNames).doesNotContain("module-info");
        }
    }
}
