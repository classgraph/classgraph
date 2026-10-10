/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests closing a single {@link VfsRoot} while the {@link Vfs} that opened it stays open: that the close removes
 * the root from the {@link Vfs} cache before it releases what the root owns, releases the root's pooled
 * {@link ModuleReader} instances, turns further reads away, and leaves the {@link Vfs} and every other root
 * untouched -- and that {@link Vfs#close()} closes every root the {@link Vfs} opened, whether or not it is still in
 * the cache. A root whose resources outlived it would strand an open {@link ModuleReader} for the rest of the life
 * of the JVM.
 */
class VfsRootCloseTest {
    /**
     * The close must take the root out of the {@link Vfs} cache before it releases what the root owns, so that the
     * window in which another thread can be handed a root that is closing is as short as possible. The pooled
     * reader observes the ordering: when the close reaches it, the root must already be gone from the cache.
     */
    @Test
    void theCloseRemovesTheRootFromTheVfsBeforeReleasingItsResources() throws Exception {
        try (var vfs = new Vfs()) {
            final var moduleReference = new FakeModuleReference("test.ordering");
            final var root = (ModuleRoot) vfs.open(moduleReference);
            final var recycler = root.moduleReaderRecycler();
            recycler.recycle(recycler.acquire());
            assertThat(vfs).contains(root);
            final var rootWasStillCachedWhenReaderClosed = new AtomicBoolean();
            moduleReference.onReaderClose = () -> {
                for (final var cachedRoot : vfs) {
                    if (cachedRoot == root) {
                        rootWasStillCachedWhenReaderClosed.set(true);
                    }
                }
            };

            root.close();

            assertThat(moduleReference.openedReaders).isNotEmpty();
            assertThat(moduleReference.openedReaders.get(0).closed).isTrue();
            assertThat(rootWasStillCachedWhenReaderClosed).isFalse();
            assertThat(vfs).doesNotContain(root);
        }
    }

    /**
     * Closing a module root closes the reader waiting in its pool, and one that was acquired and not yet handed
     * back. Handing that reader back afterwards must not throw, since it is a {@code close()} method that does so,
     * and asking the closed root for its recycler is turned away, since a reader opened from the dead pool would
     * stay open for the life of the JVM.
     */
    @Test
    void theCloseForceClosesTheModuleReaderRecycler() throws Exception {
        try (var vfs = new Vfs()) {
            final var moduleReference = new FakeModuleReference("test.readers");
            final var root = (ModuleRoot) vfs.open(moduleReference);
            final var recycler = root.moduleReaderRecycler();
            final var loanedReader = (FakeModuleReader) recycler.acquire();
            final var pooledReader = (FakeModuleReader) recycler.acquire();
            recycler.recycle(pooledReader);

            root.close();

            assertThat(pooledReader.closed).isTrue();
            assertThat(loanedReader.closed).isTrue();
            recycler.recycle(loanedReader);
            assertThatThrownBy(root::moduleReaderRecycler).hasMessageContaining("after the root has been closed");
        }
    }

    /**
     * A closed root refuses to be read, but the {@link Vfs} that opened it is untouched: opening the same path
     * again builds a fresh root that reads normally.
     */
    @Test
    void aClosedRootRefusesToReadWhileTheVfsStaysOpen(@TempDir final Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("widget.txt"), "widget");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.getEntry("widget.txt")).isNotNull();

            root.close();

            assertThatThrownBy(root::getEntries).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the root has been closed");
            assertThatThrownBy(root::asFileSystem).isInstanceOf(ClosedFileSystemException.class);
            final var reopenedRoot = vfs.open(tempDir.toString());
            assertThat(reopenedRoot).isNotSameAs(root);
            assertThat(reopenedRoot.getEntry("widget.txt")).isNotNull();
        }
    }

    /** Closing one root leaves every other root of the same {@link Vfs} readable. */
    @Test
    void closingOneRootLeavesTheOtherRootsWorking(@TempDir final Path firstDir, @TempDir final Path secondDir)
            throws Exception {
        Files.writeString(firstDir.resolve("first.txt"), "first");
        Files.writeString(secondDir.resolve("second.txt"), "second");
        try (var vfs = new Vfs()) {
            final var firstRoot = vfs.open(firstDir.toString());
            final var secondRoot = vfs.open(secondDir.toString());

            firstRoot.close();

            assertThat(secondRoot.getEntry("second.txt")).isNotNull();
            assertThat(vfs).doesNotContain(firstRoot).contains(secondRoot);
        }
    }

    /**
     * {@link Vfs#close()} closes every root the {@link Vfs} opened -- including one that {@link Vfs#evict(VfsRoot)}
     * removed from the cache, since eviction only lets the cache forget the root, and the root stays readable until
     * the {@link Vfs} is closed. A close that only walked the cache would strand the evicted root's readers.
     */
    @Test
    void theVfsCloseClosesEveryRootItOpenedIncludingAnEvictedOne() throws Exception {
        final var vfs = new Vfs();
        final var cachedModule = new FakeModuleReference("test.cached");
        final var evictedModule = new FakeModuleReference("test.evicted");
        final var cachedRoot = (ModuleRoot) vfs.open(cachedModule);
        final var evictedRoot = (ModuleRoot) vfs.open(evictedModule);
        final var cachedRecycler = cachedRoot.moduleReaderRecycler();
        cachedRecycler.recycle(cachedRecycler.acquire());
        final var evictedRecycler = evictedRoot.moduleReaderRecycler();
        evictedRecycler.recycle(evictedRecycler.acquire());
        vfs.evict(evictedRoot);

        vfs.close();

        assertThat(cachedModule.openedReaders).isNotEmpty().allMatch(reader -> reader.closed);
        assertThat(evictedModule.openedReaders).isNotEmpty().allMatch(reader -> reader.closed);
    }

    /** Closing a root that is already closed does nothing, and does not throw. */
    @Test
    void closingARootTwiceHasNoFurtherEffect(@TempDir final Path tempDir) throws Exception {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            root.close();
            root.close();
            assertThatThrownBy(root::getEntries).hasMessageContaining("after the root has been closed");
        }
    }

    /**
     * The content of every entry the stream tests read: random, so that a deflated copy of it is no smaller, and
     * large enough that no stream has read all of it after its first byte.
     */
    private static final byte[] CONTENT = new byte[100_000];
    static {
        new Random(0).nextBytes(CONTENT);
    }

    /**
     * Add an entry holding {@link #CONTENT}, or the given bytes, to a jarfile.
     *
     * @param zipOut
     *            the jarfile being written.
     * @param name
     *            the name of the entry.
     * @param bytes
     *            the content of the entry.
     * @param stored
     *            true to store the entry uncompressed, false to deflate it.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void putEntry(final ZipOutputStream zipOut, final String name, final byte[] bytes,
            final boolean stored) throws IOException {
        final var entry = new ZipEntry(name);
        if (stored) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            final var crc = new CRC32();
            crc.update(bytes);
            entry.setCrc(crc.getValue());
        }
        zipOut.putNextEntry(entry);
        zipOut.write(bytes);
        zipOut.closeEntry();
    }

    /**
     * Write a jarfile holding a stored and a deflated entry at its top level, a stored entry within a package root,
     * and a stored jarfile that holds a stored and a deflated entry of its own.
     *
     * @return the bytes of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] jarBytes() throws IOException {
        final var innerBytesOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(innerBytesOut)) {
            putEntry(zipOut, "stored.bin", CONTENT, /* stored = */ true);
            putEntry(zipOut, "deflated.bin", CONTENT, /* stored = */ false);
        }
        final var bytesOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(bytesOut)) {
            putEntry(zipOut, "stored.bin", CONTENT, /* stored = */ true);
            putEntry(zipOut, "deflated.bin", CONTENT, /* stored = */ false);
            putEntry(zipOut, "pkg/stored.bin", CONTENT, /* stored = */ true);
            putEntry(zipOut, "lib/inner.jar", innerBytesOut.toByteArray(), /* stored = */ true);
        }
        return bytesOut.toByteArray();
    }

    /**
     * Open a stream on an entry, read its first byte, close the given root, and check that the next read of the
     * stream is refused with an {@link IOException} that names the entry -- and that the stream can still be closed
     * afterward.
     *
     * @param root
     *            the root to look the entry up in, and to close.
     * @param entryPath
     *            the path of the entry within the root.
     * @throws Exception
     *             if the entry could not be read before the close, or the check fails.
     */
    private static void assertStreamRefusesToReadAfterRootClose(final VfsRoot root, final String entryPath)
            throws Exception {
        final var entry = Objects.requireNonNull(root.getEntry(entryPath));
        try (var inputStream = entry.open()) {
            assertThat(inputStream.read()).isEqualTo(CONTENT[0] & 0xff);

            root.close();

            assertThatThrownBy(inputStream::readAllBytes).isInstanceOf(IOException.class)
                    .hasMessageContaining(entry.getPath()).hasMessageContaining("after the root has been closed");
            assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the root has been closed");
        }
    }

    /** A stream on a file of a directory refuses to read once its root is closed. */
    @Test
    void aDirEntryStreamRefusesToReadAfterTheRootCloses(@TempDir final Path tempDir) throws Exception {
        Files.write(tempDir.resolve("file.bin"), CONTENT);
        try (var vfs = new Vfs()) {
            assertStreamRefusesToReadAfterRootClose(vfs.open(tempDir.toString()), "file.bin");
        }
    }

    /**
     * A stream on an entry of a jarfile file refuses to read once its root is closed, with an error that names the
     * entry, rather than with whatever the released file channel throws.
     */
    @Test
    void aFileJarStreamRefusesToReadAfterTheRootCloses(@TempDir final Path tempDir) throws Exception {
        final var jarFile = tempDir.resolve("test.jar");
        Files.write(jarFile, jarBytes());
        try (var vfs = new Vfs()) {
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarFile), "stored.bin");
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarFile), "deflated.bin");
        }
    }

    /** A stream on an entry of a jarfile held in memory refuses to read once its root is closed. */
    @Test
    void anInMemoryJarStreamRefusesToReadAfterTheRootCloses() throws Exception {
        final var jarBytes = jarBytes();
        try (var vfs = new Vfs()) {
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarBytes, "stored.jar"), "stored.bin");
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarBytes, "deflated.jar"), "deflated.bin");
        }
    }

    /**
     * A stream on an entry of a package root view refuses to read once the view is closed, even though the jarfile
     * the view shares its content with stays open.
     */
    @Test
    void aPackageRootViewStreamRefusesToReadAfterTheViewCloses(@TempDir final Path tempDir) throws Exception {
        final var jarFile = tempDir.resolve("test.jar");
        Files.write(jarFile, jarBytes());
        try (var vfs = new Vfs()) {
            final var jarRoot = vfs.open(jarFile);
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarFile + "!/pkg"), "stored.bin");
            assertThat(jarRoot.isClosed()).isFalse();
        }
    }

    /**
     * A stream on an entry of a jarfile stored within another jarfile refuses to read once the nested root is
     * closed, even though the enclosing jarfile stays open.
     */
    @Test
    void aNestedJarStreamRefusesToReadAfterTheNestedRootCloses(@TempDir final Path tempDir) throws Exception {
        final var jarFile = tempDir.resolve("test.jar");
        Files.write(jarFile, jarBytes());
        try (var vfs = new Vfs()) {
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarFile + "!/lib/inner.jar"), "stored.bin");
            assertStreamRefusesToReadAfterRootClose(vfs.open(jarFile + "!/lib/inner.jar"), "deflated.bin");
            assertThat(vfs.open(jarFile).isClosed()).isFalse();
        }
    }

    /** A stream on a resource of a module refuses to read once its root is closed. */
    @Test
    void aModuleEntryStreamRefusesToReadAfterTheRootCloses() throws Exception {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(ModuleFinder.ofSystem().find("java.base").orElseThrow());
            final var entry = Objects.requireNonNull(root.getEntry("java/lang/Object.class"));
            try (var inputStream = entry.open()) {
                assertThat(inputStream.read()).isEqualTo(0xca);

                root.close();

                assertThatThrownBy(inputStream::readAllBytes).isInstanceOf(IOException.class)
                        .hasMessageContaining(entry.getPath())
                        .hasMessageContaining("after the root has been closed");
            }
        }
    }

    /** A channel from {@link VfsEntry#openChannel()} refuses to read once its root is closed. */
    @Test
    void anEntryChannelRefusesToReadAfterTheRootCloses(@TempDir final Path tempDir) throws Exception {
        Files.write(tempDir.resolve("file.bin"), CONTENT);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var entry = Objects.requireNonNull(root.getEntry("file.bin"));
            try (var channel = entry.openChannel()) {
                assertThat(channel.read(ByteBuffer.allocate(10))).isEqualTo(10);

                root.close();

                assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(10))).isInstanceOf(IOException.class)
                        .hasMessageContaining(entry.getPath())
                        .hasMessageContaining("after the root has been closed");
            }
        }
    }

    /**
     * A stream opened through the {@link VfsRoot#asFileSystem()} view refuses to read once the root is closed, as
     * the view itself does.
     */
    @Test
    void aFileSystemViewStreamRefusesToReadAfterTheRootCloses() throws Exception {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarBytes(), "test.jar");
            final var entry = Objects.requireNonNull(root.getEntry("stored.bin"));
            try (var inputStream = Files.newInputStream(entry.asPath())) {
                assertThat(inputStream.read()).isEqualTo(CONTENT[0] & 0xff);

                root.close();

                assertThatThrownBy(inputStream::readAllBytes).isInstanceOf(IOException.class)
                        .hasMessageContaining(entry.getPath())
                        .hasMessageContaining("after the root has been closed");
            }
        }
    }

    /** A {@link ModuleReference} whose readers are {@link FakeModuleReader} instances. */
    private static final class FakeModuleReference extends ModuleReference {
        /** Every reader this module has opened. */
        final List<FakeModuleReader> openedReaders = new ArrayList<>();

        /** Runs when a reader of this module is closed. Replaced by a test that observes the close. */
        Runnable onReaderClose = () -> {
        };

        /**
         * Constructor.
         *
         * @param moduleName
         *            the name of the module.
         */
        FakeModuleReference(final String moduleName) {
            super(ModuleDescriptor.newModule(moduleName).build(), URI.create("fake:" + moduleName));
        }

        @Override
        public ModuleReader open() {
            final var reader = new FakeModuleReader(this);
            openedReaders.add(reader);
            return reader;
        }
    }

    /** A {@link ModuleReader} that records whether it has been closed. */
    private static final class FakeModuleReader implements ModuleReader {
        /** The module this reader was opened from, which holds the close callback. */
        private final FakeModuleReference moduleReference;

        /** True once {@link #close()} has been called. */
        private boolean closed;

        /**
         * Constructor.
         *
         * @param moduleReference
         *            the module this reader was opened from.
         */
        FakeModuleReader(final FakeModuleReference moduleReference) {
            this.moduleReference = moduleReference;
        }

        @Override
        public Optional<URI> find(final String name) {
            return Optional.empty();
        }

        @Override
        public Stream<String> list() {
            return Stream.empty();
        }

        @Override
        public void close() {
            closed = true;
            moduleReference.onReaderClose.run();
        }
    }
}
