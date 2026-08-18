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
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
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
     * {@link #tempFiles} are only ever drained, while this lock is held. A registration that has to be rejected
     * once the session is closing reads {@link #closed} under this lock too, so that it either completes before the
     * teardown takes its snapshot or is rejected.
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
                final @Nullable LogNode ignored) throws IOException {
            // Creating the recycler is a registration, so it is linearized against the teardown like any other:
            // either it completes before the teardown reads the map, in which case the teardown force-closes the
            // recycler (the map hands out a value only once its creation has completed), or it sees the session
            // already closed and is rejected. A recycler created after the teardown emptied the map would never be
            // force-closed, so every ModuleReader it went on to open would stay open for the life of the JVM
            synchronized (closeLock) {
                if (closed.get()) {
                    throw new IOException(SESSION_CLOSED);
                }
                return new Recycler<>() {
                    @Override
                    public ModuleReader newInstance() throws IOException {
                        return ModuleReaderUtils.openModule(moduleReference);
                    }
                };
            }
        }
    };

    /**
     * True once {@link #beginClose()} or {@link #close(LogNode)} has been called. Written under {@link #closeLock}.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** True if a file was mapped that only the garbage collector can unmap. */
    // #939
    private final AtomicBoolean filesAwaitingUnmapping = new AtomicBoolean(false);

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
     * <p>
     * This check is only a fast path, since the caller can hold the returned map across a close. What stops a
     * recycler being created for a session that is closing is the check in the map's own
     * {@link SingletonMap#newInstance(Object, LogNode)}, which is made under {@link #closeLock}.
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
     * <p>
     * A slice calls this as the first step of its own close, before it releases anything, so a slice that has
     * started closing is never handed to the teardown -- which would find nothing left to do with it anyway.
     *
     * @param slice
     *            the {@link Slice} that is being closed.
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
     * The owner of the session ({@code Vfs}) has its own work to do before {@link #close(LogNode)} can run -- the
     * caches of opened roots and of zipfiles have to be dropped first, so that nothing can hand out a {@link Slice}
     * of a zipfile that is about to be closed. It calls this method first, and only proceeds if it is the caller
     * that won the race to close.
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
        // Under the lock, so that a recycler being created concurrently either finishes before the map is read
        // below, or sees the session as closed and is rejected
        synchronized (closeLock) {
            closed.set(true);
        }

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

        // Everything is released in the reverse of the order in which it was taken: module readers and inflaters
        // are borrowed on top of the modules and the slices, the slices are opened over the files, and a temporary
        // file can only be deleted once the slices over it have been closed and the file has been unmapped. Every
        // step is run even if an earlier one failed, since a teardown that stopped at the first failure would
        // strand a file handle, a memory mapping or a temporary file for the rest of the life of the JVM
        final var teardown = new Teardown(log);
        final var interrupted = new AtomicBoolean();
        teardown.run(() -> closeModuleReaderRecyclers(teardown, interrupted));
        teardown.run(inflaterRecycler::forceClose);
        for (final Slice slice : slicesToClose) {
            teardown.run(slice::close);
        }

        // Below JDK 22 a file is unmapped only once the garbage collector finds the mapped buffer unreachable,
        // and Windows refuses to delete, rename or overwrite a file while it is mapped. Closing the slices above
        // dropped the last reference to every mapping this session made, so ask for a collection here: without
        // one, a file that the session mapped stays locked until the next collection happens to run, which in a
        // large heap can be minutes after the scan finished, or never. This is best effort -- below JDK 22
        // nothing can unmap a file on demand, and nothing can observe that the collector has done it. Only
        // Windows pays for the collection: every other operating system lets a mapped file be deleted or
        // replaced, so releasing the mapping promptly buys nothing there.
        // #939
        if (filesAwaitingUnmapping.get() && VersionFinder.OS == OperatingSystem.Windows) {
            System.gc();
        }

        // Temp files have to be deleted last, after all PhysicalZipFiles are closed and files are unmapped
        final var rmLog = tempFilesToDelete.isEmpty() || log == null ? null : log.log("Removing temporary files");
        final var undeleted = new ArrayList<File>();
        for (final File tempFile : tempFilesToDelete) {
            teardown.run(() -> {
                if (!deleteTempFile(tempFile)) {
                    undeleted.add(tempFile);
                }
            });
        }
        if (!undeleted.isEmpty()) {
            // Windows refuses to delete a file that is still memory-mapped, and below JDK 22 a mapping is released
            // only once the garbage collector finds it unreachable -- which closing the slices above has just made
            // it, so ask for a collection and try again. (This is a second request on Windows below JDK 22, but
            // the first one is skipped on every other operating system and JDK, where a delete can still fail for
            // an unrelated reason.) If the JVM was started with -XX:+DisableExplicitGC then this is a no-op and
            // the file is left to the File#deleteOnExit() hook that makeTempFile registered.
            System.gc();
            for (final File tempFile : undeleted) {
                teardown.run(() -> {
                    if (!deleteTempFile(tempFile) && rmLog != null) {
                        rmLog.log("Removing temporary file failed: " + tempFile);
                    }
                });
            }
        }

        if (interrupted.get()) {
            interruptionChecker.interrupt();
        }
    }

    /**
     * Record that a file was unmapped by dropping the last reference to its mapped buffer, leaving it to the
     * garbage collector to unmap the file, so that {@link #close(LogNode)} knows to ask for a collection.
     */
    // #939
    public void markFileAsAwaitingUnmapping() {
        filesAwaitingUnmapping.set(true);
    }

    /**
     * Discard the pooled {@link ModuleReader} instances of every module that was read through this session.
     *
     * @param teardown
     *            the teardown that is running, so that one reader that cannot be closed does not prevent the rest
     *            from being closed.
     * @param interrupted
     *            set to true if the current thread was interrupted while the readers were being closed, so that the
     *            interruption can be signalled once the teardown is complete.
     */
    private void closeModuleReaderRecyclers(final Teardown teardown, final AtomicBoolean interrupted) {
        // Take the recyclers out of the map before closing any of them, so that nothing can be handed a recycler
        // that this teardown has already passed over. A caller asking for one after this point is asking the map
        // to build a new one, which it refuses, since the session is already marked as closed
        List<Recycler<ModuleReader, IOException>> recyclers = List.of();
        var completedWithoutInterruption = false;
        while (!completedWithoutInterruption) {
            try {
                // This waits for any reader that is still being opened, so that the snapshot is complete
                recyclers = moduleReaderRecyclerMap.values();
                completedWithoutInterruption = true;
            } catch (final InterruptedException e) {
                // Try again if interrupted
                interrupted.set(true);
            }
        }
        moduleReaderRecyclerMap.clear();
        for (final Recycler<ModuleReader, IOException> recycler : recyclers) {
            teardown.run(recycler::forceClose);
        }
    }

    /**
     * Runs the steps of a session teardown, so that a step that fails does not stop the steps after it from
     * running. A teardown never throws: a session is often closed from a path that is already handling a failure of
     * its own, which a throw from here would replace, and there is nothing the caller could do about a resource
     * that will not release either way. So a failed step is logged, and the teardown moves on to the next resource.
     */
    private static class Teardown {
        /** The log node to report a failed step to, or null to skip logging. */
        private final @Nullable LogNode log;

        /**
         * Constructor.
         *
         * @param log
         *            the log node to report a failed step to, or null to skip logging.
         */
        Teardown(final @Nullable LogNode log) {
            this.log = log;
        }

        /** One step of a session teardown. */
        @FunctionalInterface
        private interface TeardownStep {
            /**
             * Run this step.
             *
             * @throws IOException
             *             if a resource could not be closed cleanly.
             */
            void run() throws IOException;
        }

        /**
         * Run one step of the teardown, reporting any failure it throws rather than propagating it, so that the
         * steps after it still run.
         *
         * @param step
         *            the step to run.
         */
        void run(final TeardownStep step) {
            try {
                step.run();
            } catch (final IOException e) {
                // Nothing can be done about a resource that cannot be closed cleanly, and the storage behind it is
                // being discarded either way
            } catch (final RuntimeException | Error e) {
                if (log != null) {
                    log.log("Could not release a resource that the session opened", e);
                }
            }
        }
    }
}
