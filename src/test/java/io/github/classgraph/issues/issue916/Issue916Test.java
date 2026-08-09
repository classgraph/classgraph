package io.github.classgraph.issues.issue916;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * Calling {@link ClassGraph#removeTemporaryFilesAfterScan()} closed the whole {@code NestedJarHandler} -- every
 * open slice and the inflater recycler -- before the {@link ScanResult} was returned. Any subsequent read of a
 * resource, or load of a class whose bytes had to come from a jar, then failed, even though
 * {@link ScanResult#isClosed()} still reported {@code false}, and even for an ordinary jar that never needed a
 * temporary file in the first place.
 */
public class Issue916Test {
    /**
     * A plain, non-nested jar creates no temporary files, so nothing needs to be closed to remove them.
     */
    @Test
    public void resourcesAreStillReadableAfterRemoveTemporaryFilesAfterScan() throws Exception {
        final var jarURL = Issue916Test.class.getResource("/issue286.jar");
        try (var scanResult = new ClassGraph().overrideClasspath(jarURL).enableClassInfo()
                .removeTemporaryFilesAfterScan().scan()) {
            assertThat(scanResult.isClosed()).isFalse();
            assertThat(scanResult.getAllResources()).isNotEmpty();
            for (final Resource resource : scanResult.getAllResources()) {
                // Previously threw IOException("Already closed")
                assertThat(resource.load()).isNotNull();
            }
        }
    }

    /**
     * Without the flag, resources were always readable -- pin that this is unchanged.
     */
    @Test
    public void resourcesAreReadableWithoutTheFlag() throws Exception {
        final var jarURL = Issue916Test.class.getResource("/issue286.jar");
        try (var scanResult = new ClassGraph().overrideClasspath(jarURL).enableClassInfo().scan()) {
            for (final Resource resource : scanResult.getAllResources()) {
                assertThat(resource.load()).isNotNull();
            }
        }
    }
}
