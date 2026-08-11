package io.github.classgraph.base.internal.utils;

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

/**
 * Tests for {@link OffHeapMemory}.
 *
 * <p>
 * {@code Unsafe::invokeCleaner}, which was used to unmap {@code MappedByteBuffer}s, is terminally deprecated (JDK
 * 24+ warns when it is called, and it will be removed in a future JDK release). On JDK 22+, off-heap buffers are
 * therefore allocated and memory-mapped using the {@code java.lang.foreign.Arena} API, and freed/unmapped by
 * closing the arena that created them.
 */
// #939
public class OffHeapMemoryTest {
    /**
     * On JDK 22+, a direct {@link ByteBuffer} can be allocated from an arena and freed by closing the arena.
     */
    @Test
    public void arenaAllocateAndFree() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var arena = OffHeapMemory.openArena();
        assertThat(arena).isNotNull();
        final var buf = OffHeapMemory.allocateDirectByteBufferUsingArena(arena, 32);
        assertThat(buf).isNotNull();
        assertThat(buf.isDirect()).isTrue();
        assertThat(buf.capacity()).isEqualTo(32);
        buf.put(0, (byte) 42);
        assertThat(buf.get(0)).isEqualTo((byte) 42);
        assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isTrue();
        // The buffer is freed once the arena is closed, so accessing it now throws IllegalStateException
        assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * On JDK 22+, a file can be memory-mapped using an arena and unmapped by closing the arena.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written or mapped.
     */
    @Test
    public void arenaMapAndUnmapFile(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var arena = OffHeapMemory.openArena();
        assertThat(arena).isNotNull();
        final var file = tempDir.resolve("mapped.bin");
        Files.write(file, new byte[] { 1, 2, 3, 4 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var buf = OffHeapMemory.mapFileUsingArena(arena, fileChannel, 0L, 4L);
            assertThat(buf).isNotNull();
            assertThat(buf.isDirect()).isTrue();
            assertThat(buf.capacity()).isEqualTo(4);
            assertThat(buf.get(2)).isEqualTo((byte) 3);
            assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isTrue();
            // The file is unmapped once the arena is closed, so accessing the buffer now throws
            // IllegalStateException
            assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * On JDK 17 to 21, where the arena API is not available, a direct {@link ByteBuffer} is freed individually, by
     * {@code Unsafe::invokeCleaner}. That method is terminally deprecated, and is not called on JDK 22+, so this is
     * only run on the JDK versions that need it.
     */
    @Test
    public void directByteBufferIsFreedIndividuallyWhenThereIsNoArenaApi() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22);
        assertThat(OffHeapMemory.openArena()).isNull();
        assertThat(OffHeapMemory.closeDirectByteBuffer(ByteBuffer.allocateDirect(32), /* log = */ null)).isTrue();
        // A non-direct buffer has no off-heap memory to free
        assertThat(OffHeapMemory.closeDirectByteBuffer(ByteBuffer.allocate(32), /* log = */ null)).isFalse();
    }
}
