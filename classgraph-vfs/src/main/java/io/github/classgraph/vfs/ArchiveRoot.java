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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.internal.zip.JarOpener;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import io.github.classgraph.vfs.internal.zip.PhysicalZipFile;

/** A zipfile or jarfile root, which may be nested within another jarfile or scoped to a package root. */
public final class ArchiveRoot extends VfsRoot {
    /** The logical zipfile for this root. */
    private final LogicalZipFile logicalZipFile;

    /** The package root prefix within the jarfile, or {@code ""} for the root of the jarfile. */
    private final String packageRoot;

    /**
     * The enclosing root (the whole jarfile root for a package root view, or the outer jarfile root for a nested
     * jarfile), or {@code null} for a top-level jarfile.
     */
    private final @Nullable ArchiveRoot container;

    /**
     * The physical zipfile owned and closed by this root, or {@code null} if the backing storage is owned by an
     * ancestor root.
     */
    private final @Nullable PhysicalZipFile ownedZipFile;

    /**
     * Lazily built index of entries under the package root.
     *
     * @param entries
     *            the entries under the package root, in archive order.
     * @param entriesByName
     *            the entries keyed by their root-relative path.
     */
    private record EntryIndex(List<VfsEntry> entries, Map<String, VfsEntry> entriesByName) {
    }

    /** Cached entry index, initialized on first access. */
    private volatile @Nullable EntryIndex entryIndex;

    /**
     * Construct a root for an opened jarfile, taking ownership of its physical zipfile if any.
     *
     * @param vfs
     *            the owning {@link Vfs}.
     * @param container
     *            the enclosing archive root if nested, or {@code null} for a top-level jarfile.
     * @param openedJar
     *            the opened jarfile and its owned physical zipfile, if any.
     */
    ArchiveRoot(final Vfs vfs, final @Nullable ArchiveRoot container, final JarOpener.OpenedJar openedJar) {
        this(vfs, container, openedJar.zipFile(), /* packageRoot = */ "", openedJar.ownedPhysicalZipFile());
    }

    /**
     * Construct a package-root view over a jarfile owned by {@code container}.
     *
     * @param vfs
     *            the owning {@link Vfs}.
     * @param container
     *            the root of the whole jarfile.
     * @param packageRoot
     *            the package root path within the jarfile.
     */
    ArchiveRoot(final Vfs vfs, final ArchiveRoot container, final String packageRoot) {
        this(vfs, container, container.logicalZipFile, packageRoot, /* ownedZipFile = */ null);
    }

    /**
     * Constructor.
     *
     * @param vfs
     *            the owning {@link Vfs}.
     * @param container
     *            the enclosing archive root, or {@code null} for a top-level jarfile.
     * @param logicalZipFile
     *            the logical zipfile.
     * @param packageRoot
     *            the package root within the jarfile, or {@code ""} for the whole jarfile.
     * @param ownedZipFile
     *            the physical zipfile owned by this root, or {@code null} if owned by an ancestor.
     */
    private ArchiveRoot(final Vfs vfs, final @Nullable ArchiveRoot container, final LogicalZipFile logicalZipFile,
            final String packageRoot, final @Nullable PhysicalZipFile ownedZipFile) {
        super(vfs);
        this.container = container;
        this.logicalZipFile = logicalZipFile;
        this.packageRoot = packageRoot;
        this.ownedZipFile = ownedZipFile;
    }

    /**
     * Return the index of entries under the package root, building it on first access.
     *
     * @return the entry index.
     */
    private EntryIndex entryIndex() {
        var index = entryIndex;
        if (index == null) {
            final var packageRootPrefix = packageRoot.isEmpty() ? "" : packageRoot + "/";
            final List<VfsEntry> entriesTmp = new ArrayList<>(logicalZipFile.getEntries().size());
            final Map<String, VfsEntry> entriesByNameTmp = new LinkedHashMap<>();
            for (final var zipEntry : logicalZipFile.getEntries()) {
                if (zipEntry.entryNameUnversioned.startsWith(packageRootPrefix)) {
                    final var entry = new ArchiveEntry(this, zipEntry,
                            zipEntry.entryNameUnversioned.substring(packageRootPrefix.length()));
                    entriesTmp.add(entry);
                    // No two entries have the same name: LogicalZipFile keeps only the last of two entries with
                    // the same name, and only the newest version of a multi-release entry
                    entriesByNameTmp.put(entry.getPathFromRoot(), entry);
                }
            }
            // Two threads racing here each build an equivalent index, and one wins, which is harmless -- the
            // entries hold no resources
            index = new EntryIndex(Collections.unmodifiableList(entriesTmp),
                    Collections.unmodifiableMap(entriesByNameTmp));
            entryIndex = index;
        }
        return index;
    }

    /**
     * Return the underlying {@link LogicalZipFile}.
     *
     * @return the {@link LogicalZipFile}.
     */
    LogicalZipFile zipFile() {
        return logicalZipFile;
    }

    @Override
    void releaseResources(final @Nullable LogNode log) {
        if (ownedZipFile == null) {
            // A package root view, or a jarfile stored uncompressed within the jarfile that encloses it: the
            // jarfile is read through what an ancestor of this root owns, which outlives this root
            return;
        }
        try {
            ownedZipFile.close();
        } catch (final IOException | RuntimeException e) {
            if (log != null) {
                log.log("Could not release the resources opened to read " + getPath() + " : " + e);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    String fileStoreType() {
        return "archive";
    }

    @Override
    public String getPath() {
        return logicalZipFile.getPath();
    }

    @Override
    public URI getURI() {
        return URLPaths.toURI(getPath());
    }

    @Override
    public URI resolveURI(final String pathWithinRoot) {
        final var uriStr = jarURIOf(getURI().toString()) + "!/" + URLPaths.encodePath(pathWithinRoot);
        try {
            // Built directly rather than through URLPaths#toURI(String), since the root's part of this is already
            // a URI: normalizing it a second time would strip the scheme prefixes off it and re-encode what is
            // encoded already
            return new URI(uriStr);
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI for " + uriStr + " : " + e, e);
        }
    }

    @Override
    public @Nullable File getFile() {
        // A nested jarfile reports the outermost jarfile, not what its bytes happen to be read from, which would
        // be the outer jarfile if the nested jarfile is stored, but nothing, or a temporary file, if it is deflated
        return container != null ? container.getFile() : logicalZipFile.getPhysicalFile();
    }

    @Override
    public @Nullable Path getNioPath() {
        return container != null ? container.getNioPath() : logicalZipFile.getPhysicalPath();
    }

    /**
     * Return the physical {@link File} backing this jarfile (the enclosing jarfile for a stored nested jarfile, or
     * the temporary file for a deflated nested jarfile spilled to disk).
     *
     * @return the backing {@link File}, or {@code null} if backed by RAM or a non-{@link File} {@link Path}.
     */
    @Nullable
    File getPhysicalFile() {
        return logicalZipFile.getPhysicalFile();
    }

    @Override
    public String getPackageRoot() {
        return packageRoot;
    }

    /**
     * Return the root for the whole jarfile containing this package root (e.g. {@code app.jar} for
     * {@code "app.jar!/BOOT-INF/classes"}), or {@code this} if this root is not scoped to a package root.
     *
     * @return the root of the whole jarfile, or {@code this} if not opened at a package root.
     * @throws IOException
     *             if this root or the {@link Vfs} has been closed.
     */
    @Override
    public ArchiveRoot getContainerRoot() throws IOException {
        checkNotClosed(getPath());
        // A root whose package root is empty is the root of its whole jarfile already, even if that jarfile is
        // nested within another one -- its container field then holds the enclosing jarfile's root, which is its
        // parent in the ownership tree, not the root this method describes. (The null test is only for the
        // analyzer: a package root view is always built within the root of its whole jarfile.)
        return packageRoot.isEmpty() || container == null ? this : container;
    }

    /**
     * Return the manifest of the enclosing jarfile.
     *
     * @return the manifest attributes, or {@code null} if the jarfile has no manifest.
     */
    @Override
    @Nullable
    Map<String, String> readManifest() {
        return logicalZipFile.getManifest();
    }

    @Override
    void walkImpl(final VfsVisitor visitor, final @Nullable LogNode logIgnored) {
        walkEntryList(entryIndex().entries(), visitor);
    }

    @Override
    List<VfsEntry> getEntriesImpl() {
        return entryIndex().entries();
    }

    @Override
    @Nullable
    VfsEntry getEntryImpl(final String name) {
        return entryIndex().entriesByName().get(name);
    }
}
