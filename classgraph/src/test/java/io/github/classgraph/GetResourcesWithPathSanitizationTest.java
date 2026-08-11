package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ScanResult#getResourcesWithPath(String)} and
 * {@link ScanResult#getResourcesWithPathIgnoringAccept(String)} sanitize their argument without throwing, even for
 * a path that normalizes away to nothing.
 */
public class GetResourcesWithPathSanitizationTest {
    /**
     * A path that normalizes to the hierarchy root simply matches nothing, rather than throwing.
     */
    @Test
    public void pathNormalizingToRootMatchesNothing() {
        try (var scanResult = new ClassGraph()
                .acceptPackages(GetResourcesWithPathSanitizationTest.class.getPackage().getName()).scan()) {
            for (final String path : new String[] { "/..", "/.", "/../..", "//..", "/a/.." }) {
                assertThat(scanResult.getResourcesWithPath(path)).isEmpty();
                assertThat(scanResult.getResourcesWithPathIgnoringAccept(path)).isEmpty();
            }
        }
    }
}
