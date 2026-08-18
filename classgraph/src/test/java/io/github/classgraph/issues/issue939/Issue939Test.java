package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.VfsSpecAccess;

/**
 * {@code Unsafe::invokeCleaner}, which was used to unmap {@code MappedByteBuffer}s, is terminally deprecated (JDK
 * 24+ warns when it is called, and it will be removed in a future JDK release), and frees the memory whether or not
 * another thread is still reading it. ClassGraph no longer calls it on any JDK: on JDK 22+ it allocates and
 * memory-maps {@code ByteBuffer}s using the {@code java.lang.foreign.Arena} API, and frees/unmaps them by closing
 * the arena that created them; below JDK 22 it maps files through {@code FileChannel#map} and leaves them to the
 * JDK's own cleaner, which unmaps a file once every view of the mapping has become unreachable. The arena API
 * itself is tested by {@code OffHeapMemoryTest}, in the vfs library; this checks that a scan that asks for memory
 * mapping works on every supported JDK version.
 */
public class Issue939Test {
    /**
     * Scanning a jar with memory mapping works on all JDK versions, and the mapping is released again when the
     * {@link io.github.classgraph.ScanResult} is closed.
     */
    @Test
    public void scanJarWithMemoryMapping() {
        final var classGraph = new ClassGraph().enableClassInfo()
                .acceptPackages("org.springframework.boot.loader.util")
                .overrideClasspath(Issue939Test.class.getClassLoader().getResource("issue209.jar"));
        // Files are memory-mapped on Windows only, so the platform's choice is overridden here to exercise the
        // mapping path whatever platform this test runs on
        VfsSpecAccess.vfsSpecOf(classGraph).setMemoryMappingFiles(true);
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .contains("org.springframework.boot.loader.util.SystemPropertyUtils");
        }
    }
}
