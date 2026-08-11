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
 * Copyright (c) 2019 Luke Hutchison
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import org.jspecify.annotations.Nullable;

/**
 * A jarfile that was opened by an {@link ArchiveReader}, and the entries it contains.
 *
 * <p>
 * Directory entries, encrypted entries and entries stored with an unsupported compression method are not reported.
 * For a multi-release jarfile, only the newest version of each entry that this JVM can run is reported, unless
 * {@link ArchiveReader#enableMultiReleaseVersions()} was called.
 */
public class Archive {
    /** The jarfile that was opened. */
    private final LogicalZipFile logicalZipFile;

    /** The package root within the jarfile, or the empty string if the whole jarfile is the package root. */
    private final String packageRoot;

    /** The entries under the package root, in the order they appear in the jarfile's central directory. */
    private final List<ArchiveEntry> entries;

    /** The entries under the package root, keyed by name. */
    private final Map<String, ArchiveEntry> entriesByName;

    /**
     * Constructor.
     *
     * @param logicalZipFile
     *            the jarfile that was opened.
     * @param packageRoot
     *            the package root within the jarfile, or the empty string if the whole jarfile is the package root.
     */
    Archive(final LogicalZipFile logicalZipFile, final String packageRoot) {
        this.logicalZipFile = logicalZipFile;
        this.packageRoot = packageRoot;

        final var packageRootPrefix = packageRoot.isEmpty() ? "" : packageRoot + "/";
        final List<ArchiveEntry> entriesTmp = new ArrayList<>(logicalZipFile.entries.size());
        final Map<String, ArchiveEntry> entriesByNameTmp = new LinkedHashMap<>();
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

    /**
     * Returns the path of this jarfile, with {@code "!/"} separating each nested jarfile from the one that encloses
     * it. The package root is not part of the path.
     *
     * @return the path of the jarfile.
     */
    public String getPath() {
        return logicalZipFile.getPath();
    }

    /**
     * Returns the package root within this jarfile: the directory that {@link ArchiveEntry#getName()} is relative
     * to, without a trailing {@code '/'}. This is the empty string unless the path the jarfile was opened from
     * ended in a {@code "!/"} section that named a directory rather than a nested jarfile.
     *
     * @return the package root, or the empty string if the whole jarfile is the package root.
     */
    public String getPackageRoot() {
        return packageRoot;
    }

    /**
     * Returns the entries under the package root, in the order they appear in the jarfile's central directory.
     *
     * @return the entries, as an unmodifiable list.
     */
    public List<ArchiveEntry> getEntries() {
        return entries;
    }

    /**
     * Returns the entry with the given name, or null if there is no such entry. If the jarfile contains more than
     * one entry with the same name, the first one is returned, which is the one a classloader would find.
     *
     * @param name
     *            the name of the entry, relative to the package root, e.g. {@code "com/xyz/Widget.class"}.
     * @return the entry, or null if there is no entry with that name.
     */
    public @Nullable ArchiveEntry getEntry(final String name) {
        Assert.notNull(name, "name");
        return entriesByName.get(name);
    }

    /**
     * Returns the value of the {@code Automatic-Module-Name} manifest entry, or null if the jarfile's manifest does
     * not declare one.
     *
     * @return the automatic module name, or null if there is none.
     */
    public @Nullable String getAutomaticModuleName() {
        return logicalZipFile.automaticModuleNameManifestEntryValue;
    }

    /**
     * Returns the path of this jarfile, with the package root appended if there is one.
     *
     * @return the jarfile, as a string.
     */
    @Override
    public String toString() {
        return packageRoot.isEmpty() ? getPath() : getPath() + "!/" + packageRoot;
    }
}
