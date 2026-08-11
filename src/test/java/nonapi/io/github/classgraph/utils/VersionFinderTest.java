package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/** Tests that ClassGraph can find its own version number. */
public class VersionFinderTest {
    /** ClassGraph reports its own version. */
    @Test
    public void theVersionIsReported() {
        // Running from target/classes there is no jar manifest and no Maven metadata, so the version has to come
        // from the pom.xml
        assertThat(ClassGraph.getVersion()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
