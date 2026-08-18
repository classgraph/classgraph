package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * Issue 939: {@code Unsafe::invokeCleaner}, which was used to unmap {@code MappedByteBuffer}s, is terminally
 * deprecated (JDK 24+ warns when it is called, and it will be removed in a future JDK release), and frees the
 * memory whether or not another thread is still reading it. On JDK 22+, ClassGraph allocates and memory-maps
 * {@code ByteBuffer}s using the {@code java.lang.foreign.Arena} API instead, and frees/unmaps them by closing the
 * arena that created them. Below JDK 22 there are no arenas, and files are still memory-mapped and unmapped with
 * {@code Unsafe::invokeCleaner} -- but only once the {@link ScanResult} has been closed and the caller has closed
 * every {@link Resource} it read a buffer from, since that method frees the address range whether or not anything
 * is still reading it.
 */
public class Issue939Test {
    /** The content written into the stored zip entry of the jarfile that the tests below scan. */
    private static final byte[] STORED_ENTRY_CONTENT = "the content of a stored zip entry"
            .getBytes(StandardCharsets.UTF_8);

    /** The file through which Linux says which files this process has memory-mapped. */
    private static final Path PROC_SELF_MAPS = Paths.get("/proc/self/maps");

    /**
     * Write a jarfile holding a single stored entry named {@code stored.txt}. A stored entry is read in place from
     * the mapping of the jarfile; a deflated entry would instead be inflated into a buffer of its own, which would
     * not exercise the mapping.
     *
     * @param jarPath
     *            where to write the jarfile
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJarWithAStoredEntry(final Path jarPath) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            final ZipEntry storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(STORED_ENTRY_CONTENT.length);
            storedEntry.setCompressedSize(STORED_ENTRY_CONTENT.length);
            final CRC32 crc = new CRC32();
            crc.update(STORED_ENTRY_CONTENT);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(STORED_ENTRY_CONTENT);
            zipOut.closeEntry();
        }
    }

    /**
     * Whether a file is currently memory-mapped by this JVM. Only Linux can tell, through {@code /proc/self/maps},
     * so a caller has to check that that file is readable before believing the answer.
     *
     * @param file
     *            the file to look for
     * @return true if the file is memory-mapped
     * @throws IOException
     *             if {@code /proc/self/maps} could not be read
     */
    private static boolean isMemoryMapped(final Path file) throws IOException {
        final String fileName = file.getFileName().toString();
        for (final String line : Files.readAllLines(PROC_SELF_MAPS, StandardCharsets.UTF_8)) {
            if (line.endsWith(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scanning a jar with memory mapping enabled works on all JDK versions, mapping into an arena on JDK 22+ and
     * with {@code FileChannel#map} below that.
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
     * A {@link Resource} that the caller has not closed yet keeps the file its buffer is a view of mapped, even
     * after the {@link ScanResult} that mapped the file has been closed. Below JDK 22 a file is unmapped by
     * freeing its address range, so a scan that unmapped a file the caller could still read would not merely hand
     * back stale content -- the read would take a SIGSEGV that kills the JVM. (From JDK 22 the arena is closed
     * with the scan and such a read throws {@link IllegalStateException} instead, so this only covers the JDK
     * versions where the memory really does go away.)
     *
     * @param tempDir
     *            a temporary directory to write the jarfile to be scanned into
     * @throws IOException
     *             if the jarfile could not be written or read
     */
    // #939
    @Test
    public void anOpenResourceKeepsTheFileMappedAfterTheScanIsClosed(@TempDir final Path tempDir)
            throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the arena is closed with the scan");
        final Path jarPath = tempDir.resolve("mapped-jar-entry.jar");
        writeJarWithAStoredEntry(jarPath);

        final Resource resource;
        final ByteBuffer byteBuffer;
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").enableMemoryMapping()
                .overrideClasspath(jarPath.toString()).scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            resource = resources.get(0);
            byteBuffer = resource.read();
            // The buffer of a resource of a mapped jarfile aliases the mapping, so it is direct
            assertThat(byteBuffer.isDirect()).isTrue();
        }

        // The scan is closed, but this resource is not, so the jarfile its buffer is a view of is still mapped
        assertThat(byteBuffer.get(0)).isEqualTo(STORED_ENTRY_CONTENT[0]);
        resource.close();
    }

    /**
     * A jarfile that a scan memory-mapped can be deleted as soon as the {@link ScanResult} is closed, with no
     * collection and no retry in between. Windows refuses to delete a file while it is mapped, so this is the
     * property that makes memory mapping usable there at all. That a slice unmaps its file when it closes is
     * tested by {@code SliceTest}; what this adds is that closing a {@link ScanResult} reaches that.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile to be scanned into
     * @throws IOException
     *             if the jarfile could not be written, read or deleted
     */
    // #939
    @Test
    public void aMappedJarCanBeDeletedOnceTheScanIsClosed(@TempDir final Path tempDir) throws IOException {
        final Path jarPath = tempDir.resolve("deleted-after-the-scan.jar");
        writeJarWithAStoredEntry(jarPath);

        // Only Linux can be asked which files are mapped, so the two assertions that the jarfile was mapped, and
        // then was not, are skipped elsewhere. The delete runs everywhere, and it is the delete that fails if a
        // scan on Windows leaves the files it mapped in place.
        final boolean canTellWhatIsMapped = Files.isReadable(PROC_SELF_MAPS);
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").enableMemoryMapping()
                .overrideClasspath(jarPath.toString()).scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            final Resource resource = resources.get(0);
            try {
                // The buffer of a resource of a mapped jarfile aliases the mapping, so it is direct
                assertThat(resource.read().isDirect()).isTrue();
            } finally {
                resource.close();
            }
            if (canTellWhatIsMapped) {
                assertThat(isMemoryMapped(jarPath)).as("mapped while the scan is open").isTrue();
            }
        }

        if (canTellWhatIsMapped) {
            assertThat(isMemoryMapped(jarPath)).as("still mapped after the scan closed").isFalse();
        }
        Files.delete(jarPath);
        assertThat(Files.exists(jarPath)).isFalse();
    }
}
