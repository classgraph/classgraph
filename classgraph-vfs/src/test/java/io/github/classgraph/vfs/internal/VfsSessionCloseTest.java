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

import java.io.InputStream;
import java.lang.module.ModuleReference;

import io.github.classgraph.vfs.VfsSpec;
import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;

/**
 * Tests that a closed {@link VfsSession} refuses to register a resource that its teardown has already passed by,
 * for each of the kinds of resource it owns. A registration that slipped through would hand out a file handle, a
 * temporary file or a pooled {@link java.lang.module.ModuleReader} that nothing would ever release.
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
     * The map reference is taken before the close, as a thread that raced with the close would have: the accessor's
     * own check is only a fast path, so the check that matters is the one made while the recycler is created.
     */
    @Test
    void closedSessionRefusesToCreateAModuleReaderRecycler() throws Exception {
        final var session = newSession();
        final var moduleReaderRecyclerMap = session.moduleReaderRecyclerMap();
        session.close(/* log = */ null);
        assertThatThrownBy(() -> moduleReaderRecyclerMap.get(javaBase(), /* log = */ null))
                .hasMessageContaining("session has been closed");
    }

    /** The accessor rejects a closed session before the map is even reached. */
    @Test
    void closedSessionRefusesToHandOutTheModuleReaderRecyclerMap() {
        final var session = newSession();
        session.close(/* log = */ null);
        assertThatThrownBy(session::moduleReaderRecyclerMap).hasMessageContaining("session has been closed");
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
}
