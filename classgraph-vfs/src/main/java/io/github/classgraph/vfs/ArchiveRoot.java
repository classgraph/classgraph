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

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import org.jspecify.annotations.Nullable;

/** A zipfile or jarfile, which may itself be nested within other jarfiles. */
final class ArchiveRoot extends VfsRoot {
    /** The jarfile that was opened. */
    private final LogicalZipFile logicalZipFile;

    /** The package root within the jarfile, or the empty string if the whole jarfile is the package root. */
    private final String packageRoot;

    /**
     * The root this root was opened within: for a package root view, the root of the whole jarfile; for a nested
     * jarfile, the root of the jarfile that encloses it; null for a toplevel jarfile. This is the parent this root
     * was registered with, so closing it closes this root.
     */
    private final @Nullable ArchiveRoot container;

    /**
     * The entries under the package root, and the same entries keyed by name, built together on first use.
     *
     * @param entries
     *            the entries under the package root, in the order the jarfile lists them.
     * @param entriesByName
     *            the same entries, keyed by their path from the root.
     */
    private record EntryIndex(List<VfsEntry> entries, Map<String, VfsEntry> entriesByName) {
    }

    /** The index of the entries under the package root, built on first use. */
    private volatile @Nullable EntryIndex entryIndex;

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     * @param container
     *            the root this root is opened within -- the root of the whole jarfile for a package root view, or
     *            of the enclosing jarfile for a nested jarfile -- or null for a toplevel jarfile.
     * @param logicalZipFile
     *            the jarfile that was opened.
     * @param packageRoot
     *            the package root within the jarfile, or the empty string if the whole jarfile is the package root.
     */
    ArchiveRoot(final Vfs vfs, final @Nullable ArchiveRoot container, final LogicalZipFile logicalZipFile,
            final String packageRoot) {
        super(vfs, container);
        this.container = container;
        this.logicalZipFile = logicalZipFile;
        this.packageRoot = packageRoot;
    }

    /**
     * Returns the index of the entries under the package root, building it the first time it is asked for. A root
     * materialized only as the container of the root the caller asked to open may never have its entries listed at
     * all, so the index is not built until something reads it.
     *
     * @return the index of the entries under the package root.
     */
    private EntryIndex entryIndex() {
        var index = entryIndex;
        if (index == null) {
            final var packageRootPrefix = packageRoot.isEmpty() ? "" : packageRoot + "/";
            final List<VfsEntry> entriesTmp = new ArrayList<>(logicalZipFile.entries.size());
            final Map<String, VfsEntry> entriesByNameTmp = new LinkedHashMap<>();
            for (final var zipEntry : logicalZipFile.entries) {
                if (zipEntry.entryNameUnversioned.startsWith(packageRootPrefix)) {
                    final var entry = new ArchiveEntry(this, zipEntry,
                            zipEntry.entryNameUnversioned.substring(packageRootPrefix.length()));
                    entriesTmp.add(entry);
                    // The first entry with a given name wins, matching the order that a classloader would find
                    // them in
                    entriesByNameTmp.putIfAbsent(entry.getPathFromRoot(), entry);
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
     * Returns the jarfile this root reads.
     *
     * @return the {@link LogicalZipFile}.
     */
    LogicalZipFile zipFile() {
        return logicalZipFile;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public Kind getKind() {
        return Kind.ARCHIVE;
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
        final var rootURIStr = getURI().toString();
        // A jarfile nested within another jarfile already has a "jar:" URI, and must not be given a second one
        final var uriStr = (rootURIStr.startsWith("jar:") ? "" : "jar:") + rootURIStr + "!/"
                + URLPaths.encodePath(pathWithinRoot);
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
        return logicalZipFile.getPhysicalFile();
    }

    @Override
    public @Nullable Path getNioPath() {
        return logicalZipFile.getPhysicalPath();
    }

    @Override
    public String getPackageRoot() {
        return packageRoot;
    }

    @Override
    public VfsRoot getContainerRoot() throws IOException {
        checkNotClosed(getPath());
        // A root whose package root is empty is the root of its whole jarfile already, even if that jarfile is
        // nested within another one -- its container field then holds the enclosing jarfile's root, which is its
        // parent in the ownership tree, not the root this method describes. (The null test is only for the
        // analyzer: a package root view is always built within the root of its whole jarfile.)
        return packageRoot.isEmpty() || container == null ? this : container;
    }

    /**
     * Returns the manifest of the whole jarfile, rather than of the package root this root was opened at: a Spring
     * Boot jar's {@code Class-Path}, {@code Automatic-Module-Name} and the rest describe the jarfile, and are read
     * from the manifest at its root rather than from anything under {@code "BOOT-INF/classes/"}.
     *
     * <p>
     * The manifest was already parsed while the central directory was read, since the {@code Multi-Release}
     * attribute determines the name of every entry of the jarfile, so this costs nothing to ask for.
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
