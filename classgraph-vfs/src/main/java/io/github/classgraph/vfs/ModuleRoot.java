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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.recycler.Recycler;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.module.ModuleReaderUtils;
import org.jspecify.annotations.Nullable;

/** A module of the module path, or of the running JDK. */
final class ModuleRoot extends VfsRoot {
    /** The module. */
    private final ModuleReference moduleReference;

    /** The entries of the module, or null until {@link #getEntries()} is first called. */
    private volatile @Nullable List<VfsEntry> entries;

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
    public Kind getKind() {
        return Kind.MODULE;
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
    public @Nullable Path getNioPath() {
        final var uri = moduleReference.location().orElse(null);
        if (uri == null || !"file".equals(uri.getScheme())) {
            // A module of the running JDK has a "jrt:" location, which names nothing in the filesystem
            return null;
        }
        try {
            return Path.of(uri);
        } catch (final IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
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
        } catch (final UnsupportedOperationException e) {
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
     * Get the recycler that hands out {@link ModuleReader} instances for this module. A {@code ModuleReader} is not
     * thread-safe, so each thread that reads the module needs its own, and they are pooled rather than reopened,
     * since opening one is expensive. The recycler is closed when the {@link Vfs} is closed.
     *
     * @return the recycler.
     * @throws IOException
     *             if the module could not be opened, or if the {@link Vfs} has been closed.
     */
    Recycler<ModuleReader, IOException> moduleReaderRecycler() throws IOException {
        final var vfs = getVfs();
        vfs.checkNotClosed(getPath());
        try {
            return vfs.scanResources().moduleReaderRecyclerMap().get(moduleReference, /* log = */ null);
        } catch (final NullSingletonException | NewInstanceException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not open module " + getPath() + " : " + cause, cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening module " + getPath());
        }
    }

    @Override
    public void walk(final VfsVisitor visitor) throws IOException {
        Assert.notNull(visitor, "visitor");
        // A module reader lists the whole module in one call, so there is nothing to be saved by walking it lazily
        walkEntryList(getEntries(), visitor);
    }

    @Override
    public List<VfsEntry> getEntries() throws IOException {
        var entriesCurr = entries;
        if (entriesCurr == null) {
            final var recycler = moduleReaderRecycler();
            final List<String> resourcePaths;
            try (var moduleReader = recycler.acquireRecycleOnClose()) {
                resourcePaths = ModuleReaderUtils.list(moduleReader.get(), getPath(), getVfs().log());
            } catch (final SecurityException e) {
                throw new IOException("Could not list the contents of module " + getPath() + " : " + e, e);
            }
            // List the entries of a module in a deterministic order, since ModuleReader#list() does not specify one
            Collections.sort(resourcePaths);
            final List<VfsEntry> entriesTmp = new ArrayList<>(resourcePaths.size());
            for (final var resourcePath : resourcePaths) {
                // "Whether the stream of elements includes names corresponding to directories in the module is
                // module reader specific" -- a directory can only be told apart from a resource by its trailing '/'
                if (!resourcePath.endsWith("/")) {
                    entriesTmp.add(new ModuleEntry(this, resourcePath));
                }
            }
            entriesCurr = Collections.unmodifiableList(entriesTmp);
            // Two threads racing here each list the module, and both get an equivalent list back, which is harmless
            // -- the entries hold no resources
            entries = entriesCurr;
        }
        return entriesCurr;
    }

    @Override
    public @Nullable VfsEntry getEntry(final String name) throws IOException {
        Assert.notNull(name, "name");
        if (name.isEmpty()) {
            return null;
        }
        final var recycler = moduleReaderRecycler();
        // Ask the module reader whether it has the resource, rather than listing the whole module to find out
        try (var moduleReader = recycler.acquireRecycleOnClose()) {
            return ModuleReaderUtils.contains(moduleReader.get(), name) ? new ModuleEntry(this, name) : null;
        } catch (final SecurityException e) {
            throw new IOException("Could not search module " + getPath() + " : " + e, e);
        }
    }
}
