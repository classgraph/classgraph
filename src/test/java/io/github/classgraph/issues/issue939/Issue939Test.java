package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * {@code Unsafe::invokeCleaner}, which was used to unmap
 * {@code MappedByteBuffer}s, is terminally deprecated (JDK 24+ warns when it is
 * called, and it will be removed in a future JDK release). On JDK 22+,
 * ClassGraph now allocates and memory-maps {@code ByteBuffer}s using the
 * {@code java.lang.foreign.Arena} API, and frees/unmaps them by closing the
 * arena that created them.
 */
public class Issue939Test {
    /**
     * Scanning a jar with memory mapping enabled works on all JDK versions (via an
     * arena on JDK 22+).
     */
    @Test
    public void scanJarWithMemoryMappingEnabled() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableMemoryMapping()
                .acceptPackages("org.springframework.boot.loader.util")
                .overrideClasspath(Issue939Test.class.getClassLoader().getResource("issue209.jar")).scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .contains("org.springframework.boot.loader.util.SystemPropertyUtils");
        }
    }

    /**
     * On JDK 22+, a direct {@link ByteBuffer} can be allocated from an arena and
     * freed by closing the arena.
     */
    @Test
    public void arenaAllocateAndFree() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var reflectionUtils = new ReflectionUtils();
        final var arena = FileUtils.openArena(reflectionUtils);
        assertThat(arena).isNotNull();
        final var buf = FileUtils.allocateDirectByteBufferUsingArena(arena, 32, reflectionUtils);
        assertThat(buf).isNotNull();
        assertThat(buf.isDirect()).isTrue();
        assertThat(buf.capacity()).isEqualTo(32);
        buf.put(0, (byte) 42);
        assertThat(buf.get(0)).isEqualTo((byte) 42);
        assertThat(FileUtils.closeArena(arena, reflectionUtils, /* log = */ null)).isTrue();
        // The buffer is freed once the arena is closed, so accessing it now throws
        // IllegalStateException
        assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * On JDK 22+, a file can be memory-mapped using an arena and unmapped by
     * closing the arena.
     */
    @Test
    public void arenaMapAndUnmapFile(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var reflectionUtils = new ReflectionUtils();
        final var arena = FileUtils.openArena(reflectionUtils);
        assertThat(arena).isNotNull();
        final var file = tempDir.resolve("mapped.bin");
        Files.write(file, new byte[] { 1, 2, 3, 4 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var buf = FileUtils.mapFileUsingArena(arena, fileChannel, 0L, 4L, reflectionUtils);
            assertThat(buf).isNotNull();
            assertThat(buf.isDirect()).isTrue();
            assertThat(buf.capacity()).isEqualTo(4);
            assertThat(buf.get(2)).isEqualTo((byte) 3);
            assertThat(FileUtils.closeArena(arena, reflectionUtils, /* log = */ null)).isTrue();
            // The file is unmapped once the arena is closed, so accessing the buffer now
            // throws
            // IllegalStateException
            assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
        }
    }
}
