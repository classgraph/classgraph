package io.github.classgraph.issues.issue340;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.base.internal.utils.VersionFinder;

/**
 * Unit test.
 */
public class Issue340Test {
    /** Test. */
    @Test
    public void test() {
        // Test path resolution
        assertThat(FastPathResolver.resolve("", "../../x")).isEqualTo("x");
        assertThat(FastPathResolver.resolve("/", "../../x")).isEqualTo("/x");
        assertThat(FastPathResolver.resolve("/x", "y")).isEqualTo("/x/y");
        assertThat(FastPathResolver.resolve("/x", "../y")).isEqualTo("/y");
        assertThat(FastPathResolver.resolve("/x", "../../y")).isEqualTo("/y");
        assertThat(FastPathResolver.resolve("/x/y/z", "..//..////w")).isEqualTo("/x/w");
        assertThat(FastPathResolver.resolve("/x/y/z", "//p//q"))
                .isEqualTo(VersionFinder.OS == OperatingSystem.Windows ? "//p/q" : "/p/q");

        try (var scanResult = new ClassGraph()
                .overrideClasspath(getClass().getClassLoader().getResource("issue340.jar").getPath()).scan()) {
            // issue340.jar contains Bundle-Classpath that points to jar2 and jar4. jar2 has a Class-Path entry that
            // points to jar1; jar4 has a Class-Path entry that points to jar3. jar2 and jar4 also have an invalid
            // Class-Path entry that tries to escape the parent jar root. jar1 and jar2 are deflated, jar3 and jar4
            // are stored.
            assertThat(scanResult.getAllResources().stream().map(Resource::getPath)
                    .filter(path -> path.startsWith("file")).toList())
                    .containsOnly("file1", "file2", "file3", "file4");
        }
    }
}
