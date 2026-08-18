package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.github.classgraph.base.internal.utils.VersionFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link OffHeapMemory}.
 *
 * <p>
 * {@code Unsafe::invokeCleaner}, the only other way to unmap a {@code MappedByteBuffer}, is terminally deprecated
 * (JDK 24+ warns when it is called, and it will be removed in a future JDK release), and it frees the memory
 * whether or not another thread is still reading it. Off-heap buffers are therefore allocated and memory-mapped
 * using the {@code java.lang.foreign.Arena} API, and freed/unmapped by closing the arena that created them. That
 * API was only finalized in JDK 22, so on JDK 17 to 21 no off-heap memory is allocated at all.
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
     * Only the requested region of a file is mapped, so a mapping can start part-way into the file.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written or mapped.
     */
    @Test
    public void aRegionInTheMiddleOfAFileCanBeMapped(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var arena = OffHeapMemory.openArena();
        assertThat(arena).isNotNull();
        final var file = tempDir.resolve("mapped.bin");
        Files.write(file, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var buf = OffHeapMemory.mapFileUsingArena(arena, fileChannel, /* position = */ 5L,
                    /* size = */ 3L);
            assertThat(buf).isNotNull();
            assertThat(buf.capacity()).isEqualTo(3);
            assertThat(buf.get(0)).isEqualTo((byte) 6);
            assertThat(buf.get(2)).isEqualTo((byte) 8);
        } finally {
            OffHeapMemory.closeArena(arena, /* log = */ null);
        }
    }

    /**
     * Mapping a region that runs off the end of a read-only file fails with an {@link IOException}, rather than
     * with whatever exception the reflective call wrapped it in -- the caller retries mapping after garbage
     * collection, and can only do that if it can tell an I/O failure from a failure to call the mapping API at all.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written.
     */
    @Test
    public void mappingPastTheEndOfAReadOnlyFileFailsWithAnIOException(@TempDir final Path tempDir)
            throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var arena = OffHeapMemory.openArena();
        assertThat(arena).isNotNull();
        final var file = tempDir.resolve("short.bin");
        Files.write(file, new byte[] { 1, 2, 3, 4 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> OffHeapMemory.mapFileUsingArena(arena, fileChannel, /* position = */ 0L,
                    /* size = */ 1024L)).isInstanceOf(IOException.class);
        } finally {
            OffHeapMemory.closeArena(arena, /* log = */ null);
        }
    }

    /** Closing an arena that has already been closed reports failure, rather than throwing. */
    @Test
    public void closingAnArenaTwiceReportsFailureRatherThanThrowing() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var arena = OffHeapMemory.openArena();
        assertThat(arena).isNotNull();
        assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isTrue();
        assertThat(OffHeapMemory.closeArena(arena, /* log = */ null)).isFalse();
    }

    /**
     * Loading the classes needed to free off-heap memory works, and works more than once -- it runs on every scan,
     * but must only do the work the first time.
     */
    @Test
    public void theClassesNeededToFreeOffHeapMemoryCanBeLoadedAheadOfTime() {
        assertThatCode(OffHeapMemory::warmUpDirectByteBufferClosing).doesNotThrowAnyException();
        assertThatCode(OffHeapMemory::warmUpDirectByteBufferClosing).doesNotThrowAnyException();
    }

    /**
     * On JDK 17 to 21 there is no arena to allocate off-heap memory from, so no off-heap memory is allocated at
     * all. The only other way to free a direct {@link ByteBuffer} is {@code Unsafe::invokeCleaner}, which frees the
     * memory whether or not another thread is still reading it, so it is never called.
     */
    @Test
    public void thereIsNoArenaBelowJdk22() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22);
        assertThat(OffHeapMemory.openArena()).isNull();
    }

    /**
     * A file that was mapped without an arena has been unmapped by the time
     * {@link OffHeapMemory#freeUnreachableBuffers()} returns. Asking for a collection is not enough on its own: the
     * file is unmapped while the collector processes the references that the collection found, which happens after
     * {@link System#gc()} has returned, so a file quite often is still mapped at that point.
     *
     * <p>
     * Only Linux can see whether a file is still mapped, through {@code /proc/self/maps}, so this is skipped
     * everywhere else.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written or mapped.
     */
    // #939
    @Test
    public void aFileIsUnmappedBeforeFreeUnreachableBuffersReturns(@TempDir final Path tempDir) throws IOException {
        final var maps = Path.of("/proc/self/maps");
        assumeTrue(Files.isReadable(maps), "only Linux can tell whether a file is still mapped");
        final var file = tempDir.resolve("unmapped-by-collection.bin");
        Files.write(file, new byte[4096]);
        final var fileName = file.getFileName().toString();
        // Repeated, since a mapping that outlives the request to collect is a race that is not lost every time
        for (var round = 0; round < 100; round++) {
            mapTheWholeFileAndDropTheMapping(file);
            OffHeapMemory.freeUnreachableBuffers();
            assertThat(Files.readAllLines(maps)).as("still mapped in round %d", round)
                    .noneMatch(line -> line.endsWith(fileName));
        }
    }

    /**
     * Map a whole file and return, dropping every reference to the mapping, including the stack frame that held the
     * mapped buffer.
     *
     * @param file
     *            the file to map.
     * @throws IOException
     *             if the file could not be mapped.
     */
    private static void mapTheWholeFileAndDropTheMapping(final Path file) throws IOException {
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            // Mapped without an arena, which is how a file is mapped below JDK 22
            final var mapped = fileChannel.map(MapMode.READ_ONLY, 0L, Files.size(file));
            assertThat(mapped.get(0)).isEqualTo((byte) 0);
        }
    }
}
