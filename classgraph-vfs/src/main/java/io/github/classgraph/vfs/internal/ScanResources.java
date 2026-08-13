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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.recycler.Recycler;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.base.internal.utils.ModuleReaderUtils;
import io.github.classgraph.vfs.internal.slice.Slice;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import org.jspecify.annotations.Nullable;

/**
 * The resources that a single reading session opens and owns, and that have to be released again when the session
 * is closed: the {@link Slice} instances that hold open file handles or memory mappings, the temporary files that
 * extracted nested jars were spilled to, the pool of {@link Inflater} instances used to inflate deflated zip
 * entries, and the pool of {@link ModuleReader} instances used to read modules. Also carries the
 * {@link VfsScanSpec} that every part of the reader needs.
 *
 * <p>
 * Once {@link #close(LogNode)} has been called, the methods that register a new resource throw
 * {@link NullPointerException} rather than silently handing out a resource that nothing will ever close. The
 * methods that release a resource stay callable, since releasing something twice has to be harmless.
 */
public class ScanResources {
    /** The settings that govern how archives are read. */
    public final VfsScanSpec vfsScanSpec;

    /** The interruption checker. */
    private final InterruptionChecker interruptionChecker;

    /** {@link Slice} instances that are currently open. Set to null by {@link #close(LogNode)}. */
    private @Nullable Set<Slice> openSlices = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Any temporary files created while scanning. Set to null by {@link #close(LogNode)}. */
    private @Nullable Set<File> tempFiles = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** A recycler for {@link Inflater} instances. */
    private final Recycler<RecyclableInflater, RuntimeException> inflaterRecycler = new Recycler<>() {
        @Override
        public RecyclableInflater newInstance() {
            return new RecyclableInflater();
        }
    };

    /**
     * A singleton map from a {@link ModuleReference} to a {@link ModuleReader} recycler for the module. Set to null
     * by {@link #close(LogNode)}.
     */
    private @Nullable SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
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

    /** True once {@link #beginClose()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructor.
     *
     * @param vfsScanSpec
     *            the settings that govern how archives are read
     * @param interruptionChecker
     *            the interruption checker
     */
    public ScanResources(final VfsScanSpec vfsScanSpec, final InterruptionChecker interruptionChecker) {
        this.vfsScanSpec = vfsScanSpec;
        this.interruptionChecker = interruptionChecker;
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Get the map from {@link ModuleReference} to {@link ModuleReader} recycler.
     *
     * @return the map
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    public SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
            moduleReaderRecyclerMap() {
        return Objects.requireNonNull(moduleReaderRecyclerMap);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Mark a {@link Slice} as open, so that it is closed when these resources are closed.
     *
     * @param slice
     *            the {@link Slice} that was just opened.
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    public void markSliceAsOpen(final Slice slice) {
        Objects.requireNonNull(openSlices).add(slice);
    }

    /**
     * Mark a {@link Slice} as closed. Unlike {@link #markSliceAsOpen(Slice)}, this does nothing rather than
     * throwing once {@link #close(LogNode)} has been called: a slice can be closed after these resources have been
     * torn down (for example when something that was still reading from the slice is closed afterwards), and
     * closing something twice has to be harmless.
     *
     * @param slice
     *            the {@link Slice} that was just closed.
     */
    public void markSliceAsClosed(final Slice slice) {
        final var openSlicesCurr = openSlices;
        if (openSlicesCurr != null) {
            openSlicesCurr.remove(slice);
        }
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
     *             If the temporary file could not be created.
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    public File makeTempFile(final String filePathBase, final boolean onlyUseLeafname) throws IOException {
        final var tempFile = File.createTempFile("ClassGraph--", FileUtils.TEMP_FILENAME_LEAF_SEPARATOR
                + sanitizeFilename(onlyUseLeafname ? leafname(filePathBase) : filePathBase));
        tempFile.deleteOnExit();
        Objects.requireNonNull(tempFiles).add(tempFile);
        return tempFile;
    }

    /**
     * Attempt to remove a temporary file.
     *
     * @param tempFile
     *            the temp file
     * @throws IOException
     *             If the temporary file could not be removed.
     * @throws SecurityException
     *             If the temporary file is inaccessible.
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    private void removeTempFile(final File tempFile) throws IOException, SecurityException {
        if (Objects.requireNonNull(tempFiles).remove(tempFile)) {
            Files.delete(tempFile.toPath());
        } else {
            throw new IOException("Not a temp file: " + tempFile);
        }
    }

    /**
     * Check whether any temporary files were created during the scan.
     *
     * @return true if at least one temporary file was created and has not yet been removed.
     */
    public boolean hasTempFiles() {
        final var tempFilesCurr = tempFiles;
        return tempFilesCurr != null && !tempFilesCurr.isEmpty();
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
     *             if these resources have already been closed.
     */
    public InputStream openInflaterInputStream(final InputStream rawInputStream) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read from a jarfile after the resources backing it have been closed. "
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
     * Mark these resources as closed, so that nothing new can be opened from them while they are being torn down.
     *
     * <p>
     * The owner of these resources ({@code NestedJarHandler}) has its own work to do before {@link #close(LogNode)}
     * can run -- the zipfile caches have to be dropped first, so that nothing can hand out a {@link Slice} of a
     * zipfile that is about to be closed. It calls this method first, and only proceeds if it is the caller that
     * won the race to close.
     *
     * @return true if this call was the one that marked the resources as closed, i.e. false if they were already
     *         closed.
     */
    public boolean beginClose() {
        return !closed.getAndSet(true);
    }

    /**
     * Close all open {@link Slice} instances, discard the pooled {@link ModuleReader} and {@link Inflater}
     * instances, and delete any temporary files. Must be preceded by a call to {@link #beginClose()} that returned
     * true.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    public void close(final @Nullable LogNode log) {
        var interrupted = false;
        final var recyclerMap = moduleReaderRecyclerMap;
        if (recyclerMap != null) {
            var completedWithoutInterruption = false;
            while (!completedWithoutInterruption) {
                try {
                    for (final Recycler<ModuleReader, IOException> recycler : recyclerMap.values()) {
                        recycler.forceClose();
                    }
                    completedWithoutInterruption = true;
                } catch (final InterruptedException e) {
                    // Try again if interrupted
                    interrupted = true;
                }
            }
            recyclerMap.clear();
            moduleReaderRecyclerMap = null;
        }
        final var openSlicesCurr = openSlices;
        if (openSlicesCurr != null) {
            while (!openSlicesCurr.isEmpty()) {
                for (final Slice slice : new ArrayList<>(openSlicesCurr)) {
                    try {
                        slice.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                    openSlicesCurr.remove(slice);
                }
            }
            openSlices = null;
        }
        inflaterRecycler.forceClose();
        // Temp files have to be deleted last, after all PhysicalZipFiles are closed and files are unmapped
        final var tempFilesCurr = tempFiles;
        if (tempFilesCurr != null) {
            final var rmLog = tempFilesCurr.isEmpty() || log == null ? null : log.log("Removing temporary files");
            while (!tempFilesCurr.isEmpty()) {
                for (final File tempFile : new ArrayList<>(tempFilesCurr)) {
                    try {
                        removeTempFile(tempFile);
                    } catch (IOException | SecurityException e) {
                        if (rmLog != null) {
                            rmLog.log("Removing temporary file failed: " + tempFile);
                        }
                    }
                }
            }
            tempFiles = null;
        }
        if (interrupted) {
            interruptionChecker.interrupt();
        }
    }
}
