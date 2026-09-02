package io.github.classgraph.issues.issue916;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * There used to be a way to ask for the temporary files that back extracted nested jarfiles to be removed as soon
 * as the scan completed, and asking for it closed everything the scan had opened -- every slice and the inflater
 * pool -- before the {@link ScanResult} was returned. Any subsequent read of a resource, or load of a class whose
 * bytes had to come from a jar, then failed, even though {@link ScanResult#isClosed()} still reported
 * {@code false}, and even for an ordinary jar that never needed a temporary file in the first place.
 *
 * <p>
 * Temporary files are now removed by closing whatever extracted them, so there is nothing left to ask for: a
 * {@link ScanResult} can read everything it scanned for as long as it is open, and closing it removes the temporary
 * files.
 */
public class Issue916Test {
    /**
     * Every resource of a plain, non-nested jar is readable for as long as the {@link ScanResult} is open.
     */
    @Test
    public void resourcesOfAPlainJarAreReadableUntilTheScanResultIsClosed() throws Exception {
        final var jarURL = Issue916Test.class.getResource("/issue286.jar");
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarURL).enableClassInfo().scan()) {
            assertThat(scanResult.isClosed()).isFalse();
            assertThat(scanResult.getAllResources()).isNotEmpty();
            for (final Resource resource : scanResult.getAllResources()) {
                // Previously threw IOException("Already closed")
                assertThat(resource.load()).isNotNull();
            }
        }
    }

    /**
     * A nested jarfile is extracted to a temporary file in order to be read, and its resources are readable for as
     * long as the {@link ScanResult} is open. Closing the {@link ScanResult} closes what extracted it, which
     * removes the temporary file, so a resource cannot be read afterwards.
     */
    @Test
    public void resourcesOfANestedJarAreReadableUntilTheScanResultIsClosed() throws Exception {
        final var jarPath = "jar:file://"
                + Issue916Test.class.getClassLoader().getResource("nested-jars-level1.zip").getPath()
                + "!/level2.jar!/level3.jar";
        final Resource resourceAfterClose;
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarPath).enableClassInfo().scan()) {
            final var resources = scanResult.getAllResources();
            assertThat(resources).isNotEmpty();
            for (final Resource resource : resources) {
                assertThat(resource.load()).isNotNull();
            }
            resourceAfterClose = resources.get(0);
        }
        assertThatThrownBy(resourceAfterClose::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the ScanResult is closed");
    }
}
