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
package io.github.classgraph.vfs;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.internal.module.ModuleReaderUtils;
import io.github.classgraph.vfs.internal.recycler.Recycler;
import org.jspecify.annotations.Nullable;

/** A module of the module path, or of the running JDK. */
public final class ModuleRoot extends VfsRoot {
    /** The module. */
    private final ModuleReference moduleReference;

    /** The entries of the module, or null until {@link #getEntries()} is first called. */
    private volatile @Nullable List<VfsEntry> entries;

    /**
     * The canonical path of the directory an exploded module is read from, or null until
     * {@link #canonicalModuleDir()} first finds it.
     */
    private volatile @Nullable Path canonicalModuleDir;

    /**
     * Hands out {@link ModuleReader} instances for this module, one per thread that is reading it, or null until
     * the first read needs one. Created and read under {@link #moduleReaderRecyclerLock}.
     */
    private @Nullable Recycler<ModuleReader, IOException> moduleReaderRecycler;

    /**
     * Guards the lazy creation of {@link #moduleReaderRecycler}, so that two threads asking for it at once share
     * one recycler rather than each creating their own, and the force-close of it in
     * {@link #releaseResources(LogNode)}, so that a recycler cannot be created after the close has passed over it.
     */
    private final Object moduleReaderRecyclerLock = new Object();

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     * @param moduleReference
     *            the module.
     */
    ModuleRoot(final Vfs vfs, final ModuleReference moduleReference) {
        super(vfs);
        this.moduleReference = moduleReference;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    String fileStoreType() {
        return "module";
    }

    @Override
    public String getPath() {
        return moduleReference.descriptor().name();
    }

    @Override
    public URI getURI() {
        // ModuleReference#location() is empty for a module that does not know where it came from
        return moduleReference.location()
                .orElseThrow(() -> new IllegalStateException("Module " + getPath() + " has no location"));
    }

    @Override
    public URI resolveURI(final String pathWithinRoot) {
        // Only the module can say how it names something within itself: a module of the running JDK names its
        // resources with "jrt:" URIs that have nothing to do with the module's own location
        try {
            final Recycler<ModuleReader, IOException> recycler;
            final ModuleReader reader;
            try {
                recycler = moduleReaderRecycler();
                reader = acquireReader(recycler);
            } catch (final IOException e) {
                if (!isClosed()) {
                    // The module could not be opened
                    throw e;
                }
                // This root has been closed. A URI only names the path, as it does for a directory or a jarfile,
                // whose roots can still name a path once closed, so the URI is asked of a reader that is opened for
                // this one lookup, and so is not left open by the closed root
                try (var oneOffReader = ModuleReaderUtils.openModule(moduleReference)) {
                    return ModuleReaderUtils.find(oneOffReader, pathWithinRoot);
                }
            }
            try {
                return ModuleReaderUtils.find(reader, pathWithinRoot);
            } finally {
                recycler.recycle(reader);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Could not form URI for " + getPath() + "/" + pathWithinRoot + " : " + e, e);
        }
    }

    @Override
    URI uriOfPath(final String pathWithinRoot) {
        // The module will only name a resource it contains, so a directory or a missing name is named here, the way
        // the module names its resources
        final var moduleDir = getNioPath();
        if (moduleDir != null && Files.isDirectory(moduleDir)) {
            // A module that was exploded into a directory is read from that directory, so a name within it is named
            // the way a name within a directory root is, rather than as an entry of a jarfile that does not exist
            return URLPaths.toURI(moduleDir.resolve(pathWithinRoot));
        }
        final var rootURIStr = getURI().toString();
        final var entryPath = URLPaths.encodePath(pathWithinRoot);
        final String uriStr;
        if (rootURIStr.startsWith("jrt:")) {
            // A module of the running JDK names its resources below its own "jrt:/<module>" location
            uriStr = pathWithinRoot.isEmpty() ? rootURIStr
                    : rootURIStr + (rootURIStr.endsWith("/") ? "" : "/") + entryPath;
        } else {
            // A module packaged as a jarfile names its resources within the jarfile
            uriStr = (rootURIStr.startsWith("jar:") ? "" : "jar:") + rootURIStr + "!/" + entryPath;
        }
        try {
            // Built directly rather than through URLPaths#toURI(String), since the module's location is already a
            // URI, which normalizing a second time would re-encode
            return new URI(uriStr);
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI for " + uriStr + " : " + e, e);
        }
    }

    @Override
    public @Nullable Path getNioPath() {
        final var uri = moduleReference.location().orElse(null);
        if (uri == null || !"file".equals(uri.getScheme())) {
            // A module of the running JDK has a "jrt:" location, which names nothing in the filesystem
            return null;
        }
        try {
            return Path.of(uri);
        } catch (final IllegalArgumentException | FileSystemNotFoundException _) {
            return null;
        }
    }

    @Override
    public @Nullable File getFile() {
        final var path = getNioPath();
        if (path == null) {
            return null;
        }
        try {
            return path.toFile();
        } catch (final UnsupportedOperationException _) {
            // Filesystem supports the Path API but not the File API
            return null;
        }
    }

    @Override
    public ModuleReference getModuleReference() {
        return moduleReference;
    }

    @Override
    public String getModuleName() {
        return moduleReference.descriptor().name();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the recycler that hands out {@link ModuleReader} instances for this module. The readers the JDK supplies
     * for modular jarfiles and jmod files are thread-safe, but take a lock per read, so threads that share one
     * reader contend with each other, and opening a reader costs far more than a read does. So each thread that
     * reads the module borrows a pooled reader of its own. The pooled readers are closed when this root is closed,
     * which happens no later than when the {@link Vfs} is closed.
     *
     * <p>
     * A reader must be acquired from the recycler with {@link #acquireReader(Recycler)} rather than with
     * {@link Recycler#acquire()}, since this root can close between the return of this method and the acquire.
     *
     * @return the recycler.
     * @throws IOException
     *             if this root or the {@link Vfs} has been closed. The module itself cannot fail to open here,
     *             since a reader is only opened when the recycler first hands one to a thread.
     */
    Recycler<ModuleReader, IOException> moduleReaderRecycler() throws IOException {
        synchronized (moduleReaderRecyclerLock) {
            // Checked under the lock that releaseResources() takes, and even when the recycler already exists, so
            // that a closed root does not create a recycler that nothing would ever force-close: either this check
            // passes and the recycler is assigned before releaseResources() can take the lock and force-close it,
            // or the close got here first and the check throws -- before the recycler has opened anything, so a
            // recycler dropped by the throw owns nothing. The root can still close once this returns, before the
            // caller acquires a reader, which acquireReader() checks for
            checkNotClosed(getPath());
            var recycler = moduleReaderRecycler;
            if (recycler == null) {
                recycler = new Recycler<>() {
                    @Override
                    public ModuleReader newInstance() throws IOException {
                        return ModuleReaderUtils.openModule(moduleReference);
                    }
                };
                moduleReaderRecycler = recycler;
            }
            return recycler;
        }
    }

    /**
     * Acquire a {@link ModuleReader} from the recycler that {@link #moduleReaderRecycler()} returned, and hand it
     * straight back if this root has closed since then. The caller must hand the reader back to the recycler once
     * it has finished with it.
     *
     * @param recycler
     *            the recycler.
     * @return the reader.
     * @throws IOException
     *             if the module could not be opened, or if this root, or the {@link Vfs} that opened it, has been
     *             closed.
     */
    ModuleReader acquireReader(final Recycler<ModuleReader, IOException> recycler) throws IOException {
        final var reader = recycler.acquire();
        // A close that began after moduleReaderRecycler() returned may have force-closed the recycler before the
        // acquire put this reader on loan, in which case the force-close did not see it and nothing has closed it.
        // The acquire puts the reader on loan before this check, and the close marks this root closed before it
        // force-closes the recycler, so either this check sees the close, or the force-close sees the reader on
        // loan and closes it
        if (isClosed()) {
            // Handing the reader back to a force-closed recycler closes it; one the close has yet to reach is
            // pooled, and the force-close then closes it
            recycler.recycle(reader);
            checkNotClosed(getPath());
        }
        return reader;
    }

    @Override
    void releaseResources(final @Nullable LogNode log) {
        // Taking the lock that creates the recycler means a recycler being created as this root closes is either
        // assigned before this runs, and force-closed here, or its creation is turned away by the closed check it
        // makes under this lock. A reader acquired from the recycler after this runs is turned away by
        // acquireReader()
        synchronized (moduleReaderRecyclerLock) {
            final var recycler = moduleReaderRecycler;
            if (recycler != null) {
                moduleReaderRecycler = null;
                // forceClose closes the pooled readers and any reader still on loan -- a thread midway through a
                // read gets an exception from the closed reader, which is what reading a closed root does -- and
                // never throws
                recycler.forceClose();
            }
        }
    }

    @Override
    void walkImpl(final VfsVisitor visitor, final @Nullable LogNode log) throws IOException {
        // A module reader lists the whole module in one call, so there is nothing to be saved by walking it lazily.
        // The list is not put in the cache that getEntries() fills, since a walk only passes over it once, and
        // caching it would keep an object per resource in the module alive for as long as the Vfs is open
        final var entriesCurr = entries;
        walkEntryList(entriesCurr == null ? listEntries(log) : entriesCurr, visitor);
    }

    @Override
    List<VfsEntry> getEntriesImpl() throws IOException {
        var entriesCurr = entries;
        if (entriesCurr == null) {
            entriesCurr = listEntries(getVfs().log());
            // Two threads racing here each list the module, and both get an equivalent list back, which is harmless
            // -- the entries hold no resources
            entries = entriesCurr;
        }
        return entriesCurr;
    }

    /**
     * List the entries of the module, logging to the given log node.
     *
     * @param log
     *            the log node, or null to not log.
     * @return the entries.
     * @throws IOException
     *             if the module could not be listed, or if the root, or the {@link Vfs} that opened it, has been
     *             closed.
     */
    private List<VfsEntry> listEntries(final @Nullable LogNode log) throws IOException {
        final var recycler = moduleReaderRecycler();
        final List<String> resourcePaths;
        final var moduleReader = acquireReader(recycler);
        try {
            resourcePaths = ModuleReaderUtils.list(moduleReader, getPath(), log);
        } finally {
            recycler.recycle(moduleReader);
        }
        // List the entries of a module in a deterministic order, since ModuleReader#list() does not specify one
        Collections.sort(resourcePaths);
        final List<VfsEntry> entriesTmp = new ArrayList<>(resourcePaths.size());
        for (final var resourcePath : resourcePaths) {
            // "Whether the stream of elements includes names corresponding to directories in the module is
            // module reader specific" -- a directory can only be told apart from a resource by its trailing '/'
            if (resourcePath.endsWith("/")) {
                continue;
            }
            // A module reader reports a modular jarfile's entry names exactly as they are stored. A name with a
            // leading '/', a repeated separator, or a "." or ".." section in it is not one any path can be looked up
            // by, and "../x" would name a file outside the module wherever the name is used to form a path, so the
            // entry is left out. An archive root rewrites such a name instead, but a module reader can only open an
            // entry by the name it is stored under
            if (!isCanonicalEntryName(resourcePath)) {
                if (log != null) {
                    log.log("Skipping module resource with a non-canonical name: " + resourcePath);
                }
                continue;
            }
            entriesTmp.add(new ModuleEntry(this, resourcePath));
        }
        return Collections.unmodifiableList(entriesTmp);
    }

    @Override
    boolean searchesForACaseFoldedManifest() {
        // The only way to search a module for a manifest stored under a differently-cased name is to list the whole
        // module, and a module of the running JDK -- which is where nearly every module a scan reads comes from --
        // is read from the image that jlink built, whose entry names are exact, and which carries no manifest at
        // all, so that listing would be spent to find nothing
        return false;
    }

    @Override
    @Nullable
    VfsEntry getEntryImpl(final String name) throws IOException {
        // A name ending in '/' names a directory, which is not an entry, as listEntries() also decides
        if (name.isEmpty() || name.endsWith("/")) {
            return null;
        }
        // A module reader finds a modular jarfile's entry by the name it is stored under, even one with a "." or ".."
        // section in it, which listEntries leaves out, so such a name has to be turned away here
        if (!isCanonicalEntryName(name)) {
            return null;
        }
        final var recycler = moduleReaderRecycler();
        // Ask the module reader whether it has the resource, rather than listing the whole module to find out
        final var moduleReader = acquireReader(recycler);
        try {
            if (!ModuleReaderUtils.contains(moduleReader, name)) {
                return null;
            }
        } finally {
            recycler.recycle(moduleReader);
        }
        // An exploded module is a directory, and a module reader reads one through the filesystem, so on Windows and
        // macOS it answers a lookup for a name whose case does not match the name the file is stored under. This
        // method matches names exactly, so such a match is not one. A module packaged as a modular jarfile carries
        // its own exact namespace, whatever filesystem it is read from, as does a module of the running JDK
        final var moduleDir = canonicalModuleDir();
        if (moduleDir != null && isCaseFoldedMatch(moduleDir, moduleDir.resolve(name))) {
            return null;
        }
        return new ModuleEntry(this, name);
    }

    /**
     * The canonical path of the directory an exploded module is read from, which is what
     * {@link #isCaseFoldedMatch(Path, Path)} compares the real path of a file with, the same way {@link DirRoot}
     * gives it its canonicalized directory. The location of a module is not canonicalized by the JDK, so a module
     * directory reached through a symbolic link, or on Windows through an 8.3 short name, would otherwise never
     * contain the real path of any of its files, and the case check would never fire.
     *
     * @return the canonical path of the module directory, or the path it was reached through if it cannot be
     *         canonicalized, or null if the module is not exploded into a directory.
     */
    private @Nullable Path canonicalModuleDir() {
        var dir = canonicalModuleDir;
        if (dir == null) {
            final var moduleDir = getNioPath();
            if (moduleDir == null || !Files.isDirectory(moduleDir)) {
                return null;
            }
            try {
                dir = FileUtils.canonicalize(moduleDir);
            } catch (final IOException _) {
                // A directory whose canonical path cannot be found is checked against the path it was reached
                // through, as DirRoot does
                dir = moduleDir;
            }
            // Two threads racing here each compute the same path, which is harmless
            canonicalModuleDir = dir;
        }
        return dir;
    }
}
