package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * Issue 939: {@code Unsafe::invokeCleaner}, which was used to unmap {@code MappedByteBuffer}s, is terminally
 * deprecated (JDK 24+ warns when it is called, and it will be removed in a future JDK release), and frees the
 * memory whether or not another thread is still reading it. On JDK 22+, ClassGraph allocates and memory-maps
 * {@code ByteBuffer}s using the {@code java.lang.foreign.Arena} API instead, and frees/unmaps them by closing the
 * arena that created them. Below JDK 22 files are still memory-mapped, but there is no way to unmap one without
 * {@code Unsafe::invokeCleaner}, so the mapping is left to the JDK's own cleaner, which unmaps it once no view of
 * it can be reached any more.
 */
public class Issue939Test {
    /**
     * Scanning a jar with memory mapping enabled works on all JDK versions (mapping into an arena on JDK 22+, and
     * into a mapping released by the garbage collector below that).
     */
    @Test
    public void scanJarWithMemoryMappingEnabled() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableMemoryMapping()
                .acceptPackages("org.springframework.boot.loader.util")
                .overrideClasspath(Issue939Test.class.getClassLoader().getResource("issue209.jar")).scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .contains("org.springframework.boot.loader.util.SystemPropertyUtils");
        }
    }

    /** On JDK 22+, a direct {@link ByteBuffer} can be allocated from an arena and freed by closing the arena. */
    @Test
    public void arenaAllocateAndFree() {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final ReflectionUtils reflectionUtils = new ReflectionUtils();
        final Object arena = FileUtils.openArena(reflectionUtils);
        assertThat(arena).isNotNull();
        final ByteBuffer buf = FileUtils.allocateDirectByteBufferUsingArena(arena, 32, reflectionUtils);
        assertThat(buf).isNotNull();
        assertThat(buf.isDirect()).isTrue();
        assertThat(buf.capacity()).isEqualTo(32);
        buf.put(0, (byte) 42);
        assertThat(buf.get(0)).isEqualTo((byte) 42);
        assertThat(FileUtils.closeArena(arena, reflectionUtils, /* log = */ null)).isTrue();
        // The buffer is freed once the arena is closed, so accessing it now throws IllegalStateException
        assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
    }

    /** On JDK 22+, a file can be memory-mapped using an arena and unmapped by closing the arena. */
    @Test
    public void arenaMapAndUnmapFile(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final ReflectionUtils reflectionUtils = new ReflectionUtils();
        final Object arena = FileUtils.openArena(reflectionUtils);
        assertThat(arena).isNotNull();
        final Path file = tempDir.resolve("mapped.bin");
        Files.write(file, new byte[] { 1, 2, 3, 4 });
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer buf = FileUtils.mapFileUsingArena(arena, fileChannel, 0L, 4L, reflectionUtils);
            assertThat(buf).isNotNull();
            assertThat(buf.isDirect()).isTrue();
            assertThat(buf.capacity()).isEqualTo(4);
            assertThat(buf.get(2)).isEqualTo((byte) 3);
            assertThat(FileUtils.closeArena(arena, reflectionUtils, /* log = */ null)).isTrue();
            // The file is unmapped once the arena is closed, so accessing the buffer now throws
            // IllegalStateException
            assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * A file mapped without an arena, which is how a file is mapped below JDK 22, has been unmapped by the time
     * {@link FileUtils#freeUnreachableBuffers()} returns. Windows refuses to delete, rename or overwrite a file
     * while it is mapped, so a scan that returned with a file it mapped still mapped would leave it locked.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written or mapped
     */
    @Test
    public void aFileIsUnmappedBeforeFreeUnreachableBuffersReturns(@TempDir final Path tempDir) throws IOException {
        final Path maps = Paths.get("/proc/self/maps");
        assumeTrue(Files.isReadable(maps), "only Linux can tell whether a file is still mapped");
        final Path file = tempDir.resolve("unmapped-by-collection.bin");
        Files.write(file, new byte[4096]);
        final String fileName = file.getFileName().toString();
        // Repeated, since a mapping that outlives the request to collect is a race that is not lost every time
        for (int round = 0; round < 100; round++) {
            mapTheWholeFileAndDropTheMapping(file);
            FileUtils.freeUnreachableBuffers();
            assertThat(Files.readAllLines(maps)).as("still mapped in round %d", round)
                    .noneMatch(line -> line.endsWith(fileName));
        }
    }

    /**
     * Map the whole of a file without an arena, read from the mapping, then drop the last reference to it.
     *
     * @param file
     *            the file to map
     * @throws IOException
     *             if the file could not be mapped
     */
    private static void mapTheWholeFileAndDropTheMapping(final Path file) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            // Mapped without an arena, which is how a file is mapped below JDK 22
            final ByteBuffer mapped = fileChannel.map(MapMode.READ_ONLY, 0L, Files.size(file));
            assertThat(mapped.get(0)).isEqualTo((byte) 0);
        }
    }
}
