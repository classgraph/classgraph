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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.internal;

import io.github.classgraph.vfs.VfsSpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.vfs.internal.module.ModuleReaderUtils;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * One session of reading through a virtual filesystem: everything the session opens and owns, and that has to be
 * released again when the session is closed -- the {@link Slice} instances that hold open file handles or memory
 * mappings, the temporary files that extracted nested jars were spilled to, the pool of {@link Inflater} instances
 * used to inflate deflated zip entries, and the pool of {@link ModuleReader} instances used to read modules. Also
 * carries the {@link VfsSpec} that every part of the reader needs.
 *
 * <p>
 * Once {@link #beginClose()} has been called, the methods that register a new resource throw {@link IOException}
 * rather than silently handing out a resource that nothing will ever close, and they release whatever they had
 * already opened before they threw. The methods that release a resource stay callable, since releasing something
 * twice has to be harmless.
 *
 * <p>
 * Registering a resource and tearing the session down are linearized against each other by {@link #closeLock}: a
 * registration either completes before the teardown takes its snapshot, in which case the teardown releases the
 * resource, or it sees the session already closed and is rejected. There is no window in which a resource can be
 * registered into a collection that will never be drained again.
 *
 * <p>
 * A session is deliberately not {@link AutoCloseable}: tearing one down is two steps, and only its owner knows what
 * has to happen between them, so there is no single {@code close()} that is safe to call from a try-with-resources.
 */
public class VfsSession {
    /** The message of the {@link IOException} thrown by anything that needs a session that is still open. */
    private static final String SESSION_CLOSED = "The session has been closed";

    /** The settings that govern how archives are read. */
    public final VfsSpec vfsSpec;

    /** The interruption checker. */
    private final InterruptionChecker interruptionChecker;

    /**
     * Guards the transition to closed: {@link #closed} is only ever set to true, and {@link #openSlices} and
     * {@link #tempFiles} are only ever drained, while this lock is held.
     */
    private final Object closeLock = new Object();

    /** {@link Slice} instances that are currently open. Drained by {@link #close(LogNode)}. */
    private final Set<Slice> openSlices = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Any temporary files created during the session. Drained by {@link #close(LogNode)}. */
    private final Set<File> tempFiles = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** A recycler for {@link Inflater} instances. */
    private final Recycler<RecyclableInflater, RuntimeException> inflaterRecycler = new Recycler<>() {
        @Override
        public RecyclableInflater newInstance() {
            return new RecyclableInflater();
        }
    };

    /**
     * A singleton map from a {@link ModuleReference} to a {@link ModuleReader} recycler for the module. Emptied by
     * {@link #close(LogNode)}.
     */
    private final SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
    moduleReaderRecyclerMap = new SingletonMap<>() {
        @Override
        public Recycler<ModuleReader, IOException> newInstance(final ModuleReference moduleReference,
                final @Nullable LogNode ignored) {
            return new Recycler<>() {
                @Override
                public ModuleReader newInstance() throws IOException {
                    return ModuleReaderUtils.openModule(moduleReference);
                }
            };
        }
    };

    /**
     * True once {@link #beginClose()} or {@link #close(LogNode)} has been called. Written under {@link #closeLock}.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructor.
     *
     * @param vfsSpec
     *            the settings that govern how archives are read
     * @param interruptionChecker
     *            the interruption checker
     */
    public VfsSession(final VfsSpec vfsSpec, final InterruptionChecker interruptionChecker) {
        this.vfsSpec = vfsSpec;
        this.interruptionChecker = interruptionChecker;
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Check whether the session has been closed, or is being torn down.
     *
     * @return true if {@link #beginClose()} or {@link #close(LogNode)} has been called.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Get the map from {@link ModuleReference} to {@link ModuleReader} recycler.
     *
     * @return the map
     * @throws IOException
     *             if the session has already been closed, since a recycler created after the teardown drained the
     *             map would hand out {@link ModuleReader} instances that nothing would ever close.
     */
    public SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
            moduleReaderRecyclerMap() throws IOException {
        if (closed.get()) {
            throw new IOException(SESSION_CLOSED);
        }
        return moduleReaderRecyclerMap;
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Mark a {@link Slice} as open, so that it is closed when the session is closed.
     *
     * @param slice
     *            the {@link Slice} that was just opened.
     * @throws IOException
     *             if the session has already been closed, in which case the slice was not registered, and the
     *             caller has to close it itself, since the teardown has already passed it by.
     */
    public void markSliceAsOpen(final Slice slice) throws IOException {
        synchronized (closeLock) {
            if (closed.get()) {
                throw new IOException(SESSION_CLOSED);
            }
            openSlices.add(slice);
        }
    }

    /**
     * Mark a {@link Slice} as closed. Unlike {@link #markSliceAsOpen(Slice)}, this always succeeds: a slice can be
     * closed after the session has been torn down (for example when something that was still reading from the slice
     * is closed afterwards), and closing something twice has to be harmless.
     *
     * @param slice
     *            the {@link Slice} that was just closed.
     */
    public void markSliceAsClosed(final Slice slice) {
        openSlices.remove(slice);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Get the leafname of a path.
     *
     * @param path
     *            the path
     * @return the leafname
     */
    private static String leafname(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Characters that may not appear in a filename. Windows rejects every ASCII control character, and also
     * {@code " * / < > ? \ |}, whereas Linux and macOS reject only {@code /}. Windows accepts {@code :}, but treats
     * it as the start of an NTFS alternate data stream rather than as part of the filename. The remaining
     * characters are legal everywhere, but are replaced anyway so that a temporary filename can be pasted into a
     * shell command or a log message without quoting.
     */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\"*/:<>?\\\\|&= ]");

    /**
     * Replace any character that is not valid in a filename on every supported platform with an underscore. Zip
     * entry names may contain almost any byte, whereas filenames may not, so the temporary file that a nested jar
     * is extracted to cannot simply be named after the zip entry it came from.
     *
     * @param filename
     *            the filename
     * @return the sanitized filename
     */
    private static String sanitizeFilename(final String filename) {
        return UNSAFE_FILENAME_CHARS.matcher(filename).replaceAll("_");
    }

    /**
     * Create a temporary file, and mark it for deletion on exit.
     *
     * @param filePathBase
     *            The path to derive the temporary filename from.
     * @param onlyUseLeafname
     *            If true, only use the leafname of filePathBase to derive the temporary filename.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created, or if the session has already been closed, in which
     *             case the temporary file is deleted again before this method throws.
     */
    public File makeTempFile(final String filePathBase, final boolean onlyUseLeafname) throws IOException {
        final var tempFile = File.createTempFile("ClassGraph--", PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR
                + sanitizeFilename(onlyUseLeafname ? leafname(filePathBase) : filePathBase));
        tempFile.deleteOnExit();
        final boolean registered;
        synchronized (closeLock) {
            registered = !closed.get();
            if (registered) {
                tempFiles.add(tempFile);
            }
        }
        if (!registered) {
            // The session was closed while the file was being created, so the teardown will not delete it
            deleteTempFile(tempFile);
            throw new IOException(SESSION_CLOSED);
        }
        return tempFile;
    }

    /**
     * Delete a temporary file, ignoring any failure. The file was created with {@link File#deleteOnExit()}, so a
     * file that cannot be deleted now is deleted when the JVM exits.
     *
     * @param tempFile
     *            the temp file
     * @return true if the file was deleted.
     */
    private static boolean deleteTempFile(final File tempFile) {
        try {
            Files.delete(tempFile.toPath());
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Check whether any temporary files were created during the session.
     *
     * @return true if at least one temporary file was created and has not yet been removed.
     */
    public boolean hasTempFiles() {
        return !tempFiles.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Wrap an {@link InputStream} of deflated zip entry data in an {@link InputStream} that inflates it, using an
     * {@link Inflater} borrowed from the pool and handed back to it when the returned stream is closed.
     *
     * @param rawInputStream
     *            the stream of deflated bytes
     * @return the inflating input stream
     * @throws IOException
     *             if the session has already been closed.
     */
    public InputStream openInflaterInputStream(final InputStream rawInputStream) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read from a jarfile after the session backing it has been closed. "
                    + "This happens if the object that owns the jarfile was closed (e.g. by leaving the "
                    + "try-with-resources block it was opened in) before the entry was read or the class was "
                    + "loaded, or if removal of temporary files was requested and the nested jarfile was "
                    + "extracted to a temporary file, since removing the temporary file requires closing the "
                    + "jarfile that was extracted from it");
        }
        return new RecycledInflaterInputStream(rawInputStream, inflaterRecycler);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Mark the session as closed, so that nothing new can be opened from it while it is being torn down.
     *
     * <p>
     * The owner of the session ({@code NestedJarHandler}) has its own work to do before {@link #close(LogNode)} can
     * run -- the zipfile caches have to be dropped first, so that nothing can hand out a {@link Slice} of a zipfile
     * that is about to be closed. It calls this method first, and only proceeds if it is the caller that won the
     * race to close.
     *
     * @return true if this call was the one that marked the session as closed, i.e. false if it was already closed.
     */
    public boolean beginClose() {
        synchronized (closeLock) {
            return !closed.getAndSet(true);
        }
    }

    /**
     * Close all open {@link Slice} instances, discard the pooled {@link ModuleReader} and {@link Inflater}
     * instances, and delete any temporary files. Marks the session as closed if it was not already, so that nothing
     * can register a resource that this teardown has already passed by. Calling this more than once has no further
     * effect.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    public void close(final @Nullable LogNode log) {
        closed.set(true);

        var interrupted = false;
        var completedWithoutInterruption = false;
        while (!completedWithoutInterruption) {
            try {
                for (final Recycler<ModuleReader, IOException> recycler : moduleReaderRecyclerMap.values()) {
                    recycler.forceClose();
                }
                completedWithoutInterruption = true;
            } catch (final InterruptedException e) {
                // Try again if interrupted
                interrupted = true;
            }
        }
        moduleReaderRecyclerMap.clear();

        // Take the open resources away from anything that might still be registering: after the lock is released,
        // registration is rejected, since closed is true, so these snapshots are complete
        final List<Slice> slicesToClose;
        final List<File> tempFilesToDelete;
        synchronized (closeLock) {
            slicesToClose = new ArrayList<>(openSlices);
            openSlices.clear();
            tempFilesToDelete = new ArrayList<>(tempFiles);
            tempFiles.clear();
        }

        for (final Slice slice : slicesToClose) {
            try {
                slice.close();
            } catch (final IOException e) {
                // Ignore
            }
        }
        inflaterRecycler.forceClose();

        // Temp files have to be deleted last, after all PhysicalZipFiles are closed and files are unmapped
        final var rmLog = tempFilesToDelete.isEmpty() || log == null ? null : log.log("Removing temporary files");
        for (final File tempFile : tempFilesToDelete) {
            if (!deleteTempFile(tempFile) && rmLog != null) {
                rmLog.log("Removing temporary file failed: " + tempFile);
            }
        }

        if (interrupted) {
            interruptionChecker.interrupt();
        }
    }
}
