package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.Vfs;

/**
 * Tests that a {@link PathSlice} for a whole file is memory-mapped when the vfs scan spec says files are
 * memory-mapped, that a mapped slice reads back the same content as an unmapped one, and that closing a slice both
 * stops every reader of it and leaves nothing holding its memory mapping.
 */
public class PathSliceTest {
    /** The content of the test file, long enough that sub-slices of it are worth reading. */
    private static final byte[] CONTENT = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the resources owned by a scan.
     *
     * @param memoryMapFiles
     *            the value to override {@code VfsSpec#memoryMapFiles} with, so that both paths are tested whatever
     *            the platform's own choice is
     * @return the vfs
     */
    private static Vfs vfs(final boolean memoryMapFiles) {
        final var vfsSpec = new VfsSpec();
        vfsSpec.setMemoryMappingFiles(memoryMapFiles);
        return new Vfs(vfsSpec, new InterruptionChecker());
    }

    /**
     * Write the test file.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @return the path of the test file
     * @throws IOException
     *             if the file could not be written
     */
    private static Path writeTestFile(final Path tempDir) throws IOException {
        final var file = tempDir.resolve("content.bin");
        Files.write(file, CONTENT);
        return file;
    }

    /** A whole-file slice is memory-mapped if memory mapping is enabled, and reads back the file content. */
    @Test
    public void aWholeFileSliceIsMemoryMappedIfMappingIsEnabled(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            // A mapped slice is read from a direct ByteBuffer, an unmapped slice from a heap ByteBuffer
            assertThat(slice.read().isDirect()).isTrue();
            assertThat(slice.load()).isEqualTo(CONTENT);
            assertThat(slice.loadAsString()).isEqualTo(new String(CONTENT, StandardCharsets.UTF_8));
            try (var inputStream = slice.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(CONTENT);
            }
        } finally {
            slice.close();
        }
    }

    /** A sub-slice of a memory-mapped slice reads back the corresponding range of the file content. */
    @Test
    public void aSubSliceOfAMappedSliceReadsTheRightRange(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L);
            assertThat(subSlice.loadAsString()).isEqualTo("abcde");
            final var reader = subSlice.randomAccessReader();
            assertThat(reader.readString(0, 5)).isEqualTo("abcde");
            try (var inputStream = subSlice.open()) {
                assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcde");
            }
        } finally {
            slice.close();
        }
    }

    /**
     * The buffer returned for a sub-slice covers only the sub-slice: it starts at position zero, its capacity is
     * the length of the sub-slice, and there is no route from it to the bytes that surround the sub-slice in the
     * file. It is also read-only, since it may be a view of a mapping shared by every reader of the file.
     */
    @Test
    public void theBufferOfASubSliceCoversOnlyTheSubSlice(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        for (final var memoryMapFiles : new boolean[] { true, false }) {
            final var vfs = vfs(memoryMapFiles);
            final var slice = new PathSlice(file, vfs, /* log = */ null);
            try {
                final var buf = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L)
                        .read();
                assertThat(buf.position()).isZero();
                assertThat(buf.capacity()).isEqualTo(5);
                assertThat(buf.remaining()).isEqualTo(5);
                assertThat(buf.get(0)).isEqualTo((byte) 'a');
                assertThat(buf.isReadOnly()).isTrue();
                // Clearing the buffer must not widen it to the rest of the file
                buf.clear();
                assertThat(buf.remaining()).isEqualTo(5);
            } finally {
                slice.close();
            }
        }
    }

    /** The buffer returned for a whole-file slice is read-only too. */
    @Test
    public void theBufferOfAWholeFileSliceIsReadOnly(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        for (final var memoryMapFiles : new boolean[] { true, false }) {
            final var vfs = vfs(memoryMapFiles);
            final var slice = new PathSlice(file, vfs, /* log = */ null);
            try {
                final var buf = slice.read();
                assertThat(buf.isReadOnly()).isTrue();
                assertThat(buf.position()).isZero();
                assertThat(buf.capacity()).isEqualTo(CONTENT.length);
            } finally {
                slice.close();
            }
        }
    }

    /**
     * A reader that was taken before the slice was closed holds a view of the memory mapping that closing the slice
     * releases, and every other reader of the {@link io.github.classgraph.vfs.Vfs} reports a read of released
     * storage as an {@link IOException}, so this one does too -- rather than letting the arena's
     * {@link IllegalStateException} out on JDK 22 and later, or quietly reading on through a mapping the garbage
     * collector has not caught up with below JDK 22.
     */
    @Test
    public void readingAMappedSliceAfterItWasClosedThrows(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var reader = slice.randomAccessReader();
        assertThat(reader.readByte(0)).isEqualTo(CONTENT[0]);

        slice.close();

        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        assertThatThrownBy(() -> reader.readInt(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        assertThatThrownBy(() -> reader.read(0, new byte[4], 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        // And a closed slice hands out no new reader at all, rather than one that throws on its first read
        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
    }

    /**
     * A closed slice has released the file it was reading, so it reports that rather than throwing a
     * {@link NullPointerException} from the file channel it no longer has.
     */
    @Test
    public void anUnmappedSliceCannotBeReadAfterItWasClosed(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ false);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        assertThat(slice.load()).isEqualTo(CONTENT);

        slice.close();

        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
        assertThatThrownBy(slice::load).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
        assertThatThrownBy(slice::read).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
    }

    /** A whole-file slice is not memory-mapped if memory mapping was not enabled. */
    @Test
    public void aWholeFileSliceIsNotMappedIfMappingIsDisabled(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ false);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * A sub-slice reads through the toplevel slice it was cut from, so closing the toplevel slice stops the
     * sub-slice reading too, rather than letting it read on through a copy of the mapping that the close was
     * supposed to have taken away.
     */
    @Test
    public void aSubSliceCannotBeReadAfterTheToplevelSliceWasClosed(@TempDir final Path tempDir)
            throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        final var reader = subSlice.randomAccessReader();
        assertThat(reader.readString(0, 5)).isEqualTo("abcde");

        slice.close();

        assertThatThrownBy(() -> reader.readString(0, 5)).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::randomAccessReader).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::read).isInstanceOf(IOException.class);
    }

    /**
     * Closing a slice leaves nothing at all holding its memory mapping, even while a sub-slice of it is still
     * alive. Below JDK 22 the close unmaps the file by freeing its address range, so anything left holding the
     * mapped buffer would be holding a view of memory that is no longer there, and reading through it would take a
     * SIGSEGV that kills the JVM. The mapping is deliberately reachable from nothing the API hands out, so the only
     * way to watch it is by reflection.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     * @throws ReflectiveOperationException
     *             if the mapped buffer could not be read out of the slice
     */
    @Test
    public void closingASliceReleasesItsMappingEvenWhileASubSliceIsAlive(@TempDir final Path tempDir)
            throws IOException, ReflectiveOperationException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        assertThat(subSlice.loadAsString()).isEqualTo("abcde");

        final var backingByteBuffer = PathSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final var mapping = new WeakReference<>(backingByteBuffer.get(slice));
        assertThat(mapping.refersTo(null)).isFalse();

        slice.close();

        assertThat(collect(mapping)).isTrue();

        // The sub-slice is used after the check above, which is what keeps it strongly reachable across it: the
        // point of the check is that a live sub-slice is not what holds the mapping alive
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
    }

    /**
     * A stream that has been closed holds nothing of the memory mapping of the file it was reading, even while the
     * stream itself is still alive. A stream reads through a reader, and a reader of a mapped file holds a
     * duplicate of the mapped buffer, so a closed stream that still held its reader would be holding a view of the
     * address range that closing the slice freed. A stream handed out through the
     * {@link io.github.classgraph.vfs.Vfs} API can be kept by the caller for as long as it likes, so what a closed
     * one still refers to is worth checking.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     * @throws ReflectiveOperationException
     *             if the mapped buffer could not be read out of the slice
     */
    // #939
    @Test
    public void closingAStreamReleasesTheViewItHeldOfTheMapping(@TempDir final Path tempDir)
            throws IOException, ReflectiveOperationException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var inputStream = slice.open();
        assertThat(inputStream.read()).isEqualTo(CONTENT[0]);

        final var backingByteBuffer = PathSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final var mapping = new WeakReference<>(backingByteBuffer.get(slice));
        assertThat(mapping.refersTo(null)).isFalse();

        inputStream.close();
        slice.close();

        assertThat(collect(mapping)).isTrue();

        // The stream is used after the check above, which is what keeps it strongly reachable across it: the point
        // of the check is that a closed stream is not what holds the mapping alive
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class);
    }

    /**
     * Ask for garbage collections until the referent has been collected, or give up.
     *
     * @param ref
     *            a reference to the object that should have become garbage
     * @return true if the referent was collected
     */
    private static boolean collect(final WeakReference<?> ref) {
        for (var i = 0; i < 100 && !ref.refersTo(null); i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.refersTo(null);
    }

    /**
     * A file that a vfs memory-mapped can be deleted once the vfs has been closed, since closing the vfs unmaps it.
     * Windows refuses to delete a file that is still mapped. (Every other operating system lets a mapped file be
     * deleted, so this test only bites on Windows.)
     */
    // #939
    @Test
    public void aMappedFileCanBeDeletedOnceTheSessionIsClosed(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();

        // Closing the vfs closes the slice, which unmaps the file
        vfs.close(/* log = */ null);

        Files.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    /** The file through which Linux says which files this process has memory-mapped. */
    private static final Path PROC_SELF_MAPS = Path.of("/proc/self/maps");

    /**
     * Whether a file is currently memory-mapped by this JVM. Only Linux can tell, through {@code /proc/self/maps},
     * so a test that calls this has to skip itself everywhere else.
     *
     * @param file
     *            the file to look for
     * @return true if the file is memory-mapped
     * @throws IOException
     *             if {@code /proc/self/maps} could not be read
     */
    private static boolean isMemoryMapped(final Path file) throws IOException {
        final var fileName = file.getFileName().toString();
        return Files.readAllLines(PROC_SELF_MAPS).stream().anyMatch(line -> line.endsWith(fileName));
    }

    /**
     * A file that a slice memory-mapped is unmapped by the time the slice has closed, rather than at whatever later
     * moment the garbage collector would have got to it. Windows refuses to delete, rename or overwrite a file
     * while it is mapped, so a scan that left the files it mapped to the collector would leave them locked for as
     * long as the JVM went without a collection -- which in a large heap can be minutes, or forever.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or mapped
     */
    // #939
    @Test
    public void closingASliceUnmapsTheFileBeforeItReturns(@TempDir final Path tempDir) throws IOException {
        assumeTrue(Files.isReadable(PROC_SELF_MAPS), "only Linux can tell whether a file is still mapped");
        final var file = tempDir.resolve("unmapped-when-closed.bin");
        Files.write(file, CONTENT);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();
        assertThat(isMemoryMapped(file)).isTrue();

        slice.close();

        assertThat(isMemoryMapped(file)).isFalse();
        vfs.close(/* log = */ null);
    }

    /**
     * A view of the mapping keeps the file mapped after the slice that mapped it has closed, and the file is
     * unmapped as soon as the view is released. Below JDK 22, unmapping a file frees the address range whether or
     * not anything is still reading it, so a buffer that was handed to a caller has to hold the mapping open --
     * reading it after the file was unmapped would read memory that is no longer there, and take a SIGSEGV that
     * kills the JVM. (From JDK 22 the file is unmapped by closing the arena that mapped it, which makes such a read
     * throw {@link IllegalStateException} instead, so the arena is closed as soon as the slice is.)
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or mapped
     */
    // #939
    @Test
    public void aViewOfTheMappingKeepsTheFileMappedUntilItIsReleased(@TempDir final Path tempDir)
            throws IOException {
        assumeTrue(Files.isReadable(PROC_SELF_MAPS), "only Linux can tell whether a file is still mapped");
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the arena is closed with the slice");
        final var file = tempDir.resolve("held-open-by-a-view.bin");
        Files.write(file, CONTENT);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var releaseMappingView = slice.acquireMappingView();
        final var byteBuffer = slice.read();

        slice.close();

        assertThat(isMemoryMapped(file)).isTrue();
        // The file is still mapped, so the buffer taken before the close is still readable
        assertThat(byteBuffer.get(0)).isEqualTo(CONTENT[0]);

        releaseMappingView.run();

        assertThat(isMemoryMapped(file)).isFalse();
        vfs.close(/* log = */ null);
    }

    /**
     * A slice of a single resource file is not memory-mapped, even if memory mapping is enabled, since mapping and
     * unmapping a file that is read once and then closed costs more than reading it.
     */
    @Test
    public void aResourceSliceIsNotMemoryMapped(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* checkAccess = */ false, /* memoryMapWholeFile = */ false,
                /* log = */ null);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /** A slice can be opened from a {@link File} as well as from a {@link Path}, and reads the same content. */
    @Test
    public void aSliceCanBeOpenedFromAFile(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir).toFile();
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            assertThat(slice.getFile()).isEqualTo(file);
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * A file whose path cannot be represented as a {@link Path} is still opened, through the {@link File} API. The
     * only such file that can be created is an NTFS alternate data stream, whose name contains a ':', which
     * {@link Path} rejects on Windows.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written or opened
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void aFileThatIsNotAValidPathIsOpenedThroughTheFileApi(@TempDir final Path tempDir) throws IOException {
        // Write the content to an alternate data stream of a host file
        final var hostFile = tempDir.resolve("host.dat");
        Files.write(hostFile, new byte[0]);
        final var streamFile = new File(hostFile + ":content.bin");
        try (var raf = new RandomAccessFile(streamFile, "rw")) {
            raf.write(CONTENT);
        }
        // The stream's name is not a valid Path, which is what sends PathSlice down the File API
        assertThatThrownBy(streamFile::toPath).isInstanceOf(InvalidPathException.class);

        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(streamFile, vfs, /* log = */ null);
        try {
            assertThat(slice.getFile()).isEqualTo(streamFile);
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * The temporary file that a slice owns is deleted even when the delete has to wait for the file to be unmapped.
     * Below JDK 22 a mapping that the caller can still read a view of cannot be unmapped when the slice that owns
     * it closes, and Windows refuses to delete a file that is still mapped, so the delete has to be retried once
     * the last view of the mapping is released. The wait is reproduced here by taking the write permission off the
     * directory holding the file, which is what stops the delete on the operating systems that would let a mapped
     * file be deleted.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written or opened
     */
    // #939
    @Test
    public void aTempFileIsDeletedOnceTheLastViewOfItsMappingIsReleased(@TempDir final Path tempDir)
            throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the mapping is released with the slice");
        final var directory = Files.createDirectory(tempDir.resolve("extracted"));
        final var tempFile = Files.write(directory.resolve("extracted.jar"), CONTENT).toFile();
        final var vfs = vfs(/* memoryMapFiles = */ true);
        final var slice = PathSlice.forTempFile(tempFile, vfs, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();
        // A view of the mapping that the caller can still read, which stops the slice from unmapping the file
        final var releaseView = slice.acquireMappingView();

        // Take the write permission off the directory, so that the delete at close is refused and has to wait for
        // the file to be unmapped. (setWritable returns false on a Windows directory, where the permission is not
        // what a delete needs, so it is called for its effect rather than its result.)
        directory.toFile().setWritable(false);
        try {
            slice.close();
        } finally {
            directory.toFile().setWritable(true);
        }

        // Releasing the last view unmaps the file, which is the moment a delete that the mapping was in the way of
        // can be retried
        releaseView.run();

        // The temporary file is gone once the last view has been released, whether the delete had to wait for the
        // file to be unmapped or succeeded as the slice closed
        assertThat(tempFile).doesNotExist();
    }
}
