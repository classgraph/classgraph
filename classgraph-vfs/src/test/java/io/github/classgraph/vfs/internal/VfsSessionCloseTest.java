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
package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;

import io.github.classgraph.vfs.VfsSpec;
import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.slice.Slice;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;

/**
 * Tests the teardown of a {@link VfsSession}: that it releases everything it owns even if one of the resources
 * cannot be released, and that once it has run, it refuses to register a resource that it has already passed by,
 * for each of the kinds of resource it owns. A resource left behind, or a registration that slipped through, would
 * strand a file handle, a temporary file or a pooled {@link java.lang.module.ModuleReader} for the rest of the life
 * of the JVM.
 */
class VfsSessionCloseTest {
    /** A module to ask for a reader recycler for. */
    private static ModuleReference javaBase() {
        return ModuleLayer.boot().configuration().findModule("java.base").orElseThrow().reference();
    }

    /** A new session. */
    private static VfsSession newSession() {
        return new VfsSession(new VfsSpec(), new InterruptionChecker());
    }

    /**
     * A temporary file must not be registered with a closed session, since the teardown has already deleted the
     * files it knew about.
     */
    @Test
    void closedSessionRefusesToMakeATempFile() throws Exception {
        final var session = newSession();
        final var tempFile = session.makeTempFile("test.jar", /* onlyUseLeafname = */ false);
        assertThat(tempFile).exists();
        session.close(/* log = */ null);
        assertThat(tempFile).doesNotExist();
        assertThat(session.hasTempFiles()).isFalse();
        assertThatThrownBy(() -> session.makeTempFile("test.jar", /* onlyUseLeafname = */ false))
                .hasMessageContaining("session has been closed");
        // The rejected file was deleted again rather than left behind, so the session still holds no temp files
        assertThat(session.hasTempFiles()).isFalse();
    }

    /** An inflater must not be handed out by a closed session, since its recycler has already been force-closed. */
    @Test
    void closedSessionRefusesToOpenAnInflaterInputStream() {
        final var session = newSession();
        session.close(/* log = */ null);
        assertThatThrownBy(() -> session.openInflaterInputStream(InputStream.nullInputStream()))
                .hasMessageContaining("after the session backing it has been closed");
    }

    /**
     * A {@link java.lang.module.ModuleReader} recycler must not be created by a closed session. The teardown
     * force-closes every recycler in the map and then empties the map, so a recycler created afterwards would never
     * be force-closed, and every reader it went on to open would stay open for the life of the JVM.
     *
     * <p>
     * A lookup is turned away by the map itself, which was handed the session's closed flag. The check made while
     * the recycler is created is the one that closes the race against a thread that was already inside the map when
     * the session was closed, so it is asserted separately.
     */
    @Test
    void closedSessionRefusesToCreateAModuleReaderRecycler() {
        final var session = newSession();
        final var moduleReaderRecyclerMap = session.moduleReaderRecyclerMap();
        session.close(/* log = */ null);
        assertThatThrownBy(() -> moduleReaderRecyclerMap.get(javaBase(), /* log = */ null))
                .hasMessageContaining("Already closed");
        assertThatThrownBy(() -> moduleReaderRecyclerMap.newInstance(javaBase(), /* log = */ null))
                .hasMessageContaining("session has been closed");
    }

    /**
     * A recycler created before the close is force-closed by it, even for a reader that was acquired and not yet
     * handed back, and handing that reader back afterwards must not throw, since it is a {@code close()} method
     * that does so.
     */
    @Test
    void theCloseForceClosesTheModuleReaderRecyclers() throws Exception {
        final var session = newSession();
        final var recycler = session.moduleReaderRecyclerMap().get(javaBase(), /* log = */ null);
        final var reader = recycler.acquire();
        session.close(/* log = */ null);
        recycler.recycle(reader);
    }

    /**
     * A resource that cannot be released must not stop the teardown from releasing the rest of what the session
     * owns, and the resources must be released in the reverse of the order in which they were taken: a temporary
     * file can only be deleted once the slices over it have been closed and the file has been unmapped.
     */
    @Test
    void theCloseReleasesEverythingEvenAfterAResourceFailsToClose() throws Exception {
        final var session = newSession();
        final var tempFile = session.makeTempFile("test.jar", /* onlyUseLeafname = */ false);
        final var firstSlice = new UnclosableSlice(session, tempFile);
        final var secondSlice = new UnclosableSlice(session, tempFile);

        session.close(/* log = */ null);

        // The slice that could not be closed did not stop the second slice from being closed, or the temporary file
        // from being deleted
        assertThat(firstSlice.closeWasCalled).isTrue();
        assertThat(secondSlice.closeWasCalled).isTrue();
        assertThat(tempFile).doesNotExist();
        assertThat(session.hasTempFiles()).isFalse();

        // The temporary file was still there while the slices were being closed, i.e. it was deleted after them
        assertThat(firstSlice.tempFileExistedAtClose).isTrue();
        assertThat(secondSlice.tempFileExistedAtClose).isTrue();
    }

    /**
     * A toplevel {@link Slice} that cannot be closed, and that records what it saw when the teardown reached it.
     */
    private static final class UnclosableSlice extends Slice {
        /** The temporary file of the session, so that the order of the teardown can be checked. */
        private final File tempFile;

        /** True once {@link #close()} has been called. */
        private boolean closeWasCalled;

        /** True if the temporary file of the session still existed when {@link #close()} was called. */
        private boolean tempFileExistedAtClose;

        /**
         * Constructor.
         *
         * @param session
         *            the session to register with, so that its teardown closes this slice.
         * @param tempFile
         *            the temporary file of the session, so that the order of the teardown can be checked.
         * @throws IOException
         *             if the session has already been closed.
         */
        UnclosableSlice(final VfsSession session, final File tempFile) throws IOException {
            super(/* length = */ 0L, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L, session);
            this.tempFile = tempFile;
            registerAsOpen();
        }

        @Override
        public void close() {
            closeWasCalled = true;
            tempFileExistedAtClose = tempFile.exists();
            throw new IllegalStateException("Could not close this slice");
        }

        @Override
        public Slice slice(final long offset, final long length, final boolean isDeflatedZipEntry,
                final long inflatedLengthHint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomAccessReader randomAccessReader() {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] load() {
            throw new UnsupportedOperationException();
        }
    }
}
