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

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

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
