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
import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.URLPathEncoder;
import org.jspecify.annotations.Nullable;

/** A directory in a filesystem. */
final class DirRoot extends VfsRoot {
    /** The directory, made absolute and normalized. */
    private final Path dir;

    /** The path of the directory, with {@code '/'} as the separator. */
    private final String pathStr;

    /** The entries under the directory, or null until {@link #getEntries()} is first called. */
    private volatile @Nullable List<VfsEntry> entries;

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     * @param dir
     *            the directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    DirRoot(final Vfs vfs, final Path dir) throws IOException {
        super(vfs);
        try {
            this.dir = dir.toAbsolutePath().normalize();
        } catch (final IOError | SecurityException e) {
            throw new IOException("Could not resolve directory " + dir + " : " + e, e);
        }
        if (!Files.isDirectory(this.dir)) {
            throw new IOException("Not a directory: " + this.dir);
        }
        this.pathStr = FastPathResolver.resolve(FileUtils.currDirPath(), this.dir.toString());
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public Kind getKind() {
        return Kind.DIRECTORY;
    }

    @Override
    public String getPath() {
        return pathStr;
    }

    @Override
    public URI getURI() {
        try {
            // On Windows, Path#toUri() puts the server of a UNC path in the URI authority, where java.net.URL does
            // not find it again
            return URLPathEncoder.moveUNCServerIntoPath(dir.toUri());
        } catch (final IOError | SecurityException e) {
            throw new IllegalStateException("Could not form URI for " + dir + " : " + e, e);
        }
    }

    @Override
    public @Nullable File getFile() {
        try {
            return dir.toFile();
        } catch (final UnsupportedOperationException e) {
            // Filesystem supports the Path API but not the File API
            return null;
        }
    }

    @Override
    public Path getNioPath() {
        return dir;
    }

    @Override
    public List<VfsEntry> getEntries() throws IOException {
        var entriesCurr = entries;
        if (entriesCurr == null) {
            final List<VfsEntry> entriesTmp = new ArrayList<>();
            // Symlinks can make a directory tree cyclic, so record which directories have already been walked, by
            // their canonical path -- otherwise a directory that contains a symlink to one of its own ancestors
            // makes this recursion run until it runs out of stack
            listRecursively(dir, "", entriesTmp, new HashSet<>());
            entriesCurr = Collections.unmodifiableList(entriesTmp);
            // Two threads racing here each list the directory, and both get an equivalent list back, which is
            // harmless -- the entries hold no resources
            entries = entriesCurr;
        }
        return entriesCurr;
    }

    /**
     * List the files under a directory, recursing into its subdirectories.
     *
     * @param currDir
     *            the directory to list.
     * @param namePrefix
     *            the name of {@code currDir} relative to the root, with a trailing {@code '/'}, or the empty string
     *            for the root itself.
     * @param entriesOut
     *            the list to add the entries to.
     * @param visitedDirs
     *            the canonical paths of the directories that have already been listed.
     * @throws IOException
     *             if the directory could not be listed.
     */
    private void listRecursively(final Path currDir, final String namePrefix, final List<VfsEntry> entriesOut,
            final Set<Path> visitedDirs) throws IOException {
        final Path canonicalDir;
        try {
            canonicalDir = currDir.toRealPath();
        } catch (final IOException | SecurityException e) {
            // A directory that cannot be resolved is skipped, rather than aborting the whole listing
            return;
        }
        if (!visitedDirs.add(canonicalDir)) {
            return;
        }
        final List<Path> children = new ArrayList<>();
        try (var dirStream = Files.newDirectoryStream(currDir)) {
            for (final Path child : dirStream) {
                children.add(child);
            }
        } catch (final IOException | SecurityException e) {
            // A directory that cannot be opened is skipped, rather than aborting the whole listing
            return;
        }
        // List the entries of a directory in a deterministic order, since the order a filesystem returns them in is
        // not specified
        Collections.sort(children);
        for (final Path child : children) {
            final var childName = namePrefix + child.getFileName();
            if (Files.isDirectory(child)) {
                listRecursively(child, childName + "/", entriesOut, visitedDirs);
            } else if (Files.isRegularFile(child)) {
                entriesOut.add(new DirEntry(this, child, childName));
            }
        }
    }

    @Override
    public @Nullable VfsEntry getEntry(final String name) throws IOException {
        Assert.notNull(name, "name");
        if (name.isEmpty()) {
            return null;
        }
        final Path resolved;
        try {
            resolved = dir.resolve(name).normalize();
        } catch (final InvalidPathException e) {
            // A name that is not a legal path on this filesystem names nothing
            return null;
        }
        // A name containing ".." must not be able to reach outside the root, and an absolute name must not be able
        // to replace it -- Path#resolve returns the argument unchanged if it is absolute
        if (!resolved.startsWith(dir) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return new DirEntry(this, resolved, name);
    }
}
