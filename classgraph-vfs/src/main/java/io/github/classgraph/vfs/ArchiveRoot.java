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

    /** The entries under the package root, in the order they appear in the jarfile's central directory. */
    private final List<VfsEntry> entries;

    /** The entries under the package root, keyed by name. */
    private final Map<String, VfsEntry> entriesByName;

    /** The whole jarfile, without the package root applied, created on first use. */
    private volatile @Nullable VfsRoot containerRoot;

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     * @param logicalZipFile
     *            the jarfile that was opened.
     * @param packageRoot
     *            the package root within the jarfile, or the empty string if the whole jarfile is the package root.
     */
    ArchiveRoot(final Vfs vfs, final LogicalZipFile logicalZipFile, final String packageRoot) {
        super(vfs);
        this.logicalZipFile = logicalZipFile;
        this.packageRoot = packageRoot;

        final var packageRootPrefix = packageRoot.isEmpty() ? "" : packageRoot + "/";
        final List<VfsEntry> entriesTmp = new ArrayList<>(logicalZipFile.entries.size());
        final Map<String, VfsEntry> entriesByNameTmp = new LinkedHashMap<>();
        for (final var zipEntry : logicalZipFile.entries) {
            if (zipEntry.entryNameUnversioned.startsWith(packageRootPrefix)) {
                final var entry = new ArchiveEntry(this, zipEntry,
                        zipEntry.entryNameUnversioned.substring(packageRootPrefix.length()));
                entriesTmp.add(entry);
                // The first entry with a given name wins, matching the order that a classloader would find them in
                entriesByNameTmp.putIfAbsent(entry.getName(), entry);
            }
        }
        this.entries = Collections.unmodifiableList(entriesTmp);
        this.entriesByName = Collections.unmodifiableMap(entriesByNameTmp);
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
        final var path = getPath();
        try {
            return new URI(URLPaths.normalizeURLPath(path));
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI for " + path + " : " + e, e);
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
        if (packageRoot.isEmpty()) {
            return this;
        }
        var root = containerRoot;
        if (root == null) {
            synchronized (this) {
                root = containerRoot;
                if (root == null) {
                    containerRoot = root = new ArchiveRoot(getVfs(), logicalZipFile, "");
                }
            }
        }
        return root;
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
        walkEntryList(entries, visitor);
    }

    @Override
    List<VfsEntry> getEntriesImpl() {
        return entries;
    }

    @Override
    @Nullable
    VfsEntry getEntryImpl(final String name) {
        return entriesByName.get(name);
    }
}
