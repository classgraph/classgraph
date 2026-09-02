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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** A directory in a filesystem. */
public final class DirRoot extends VfsRoot {
    /** The directory, canonicalized. */
    private final Path dir;

    /** The path of the directory, with {@code '/'} as the separator, as reported by {@link #getPath()}. */
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
        Path absoluteDir;
        try {
            absoluteDir = dir.toAbsolutePath().normalize();
        } catch (final IOError | SecurityException e) {
            throw new IOException("Could not resolve directory " + dir + " : " + e, e);
        }
        if (!FileUtils.canReadAndIsDir(absoluteDir)) {
            throw new IOException("Not a readable directory: " + absoluteDir);
        }
        try {
            // A root is named by the canonical path of the directory that backs it, the same way a jarfile root is
            // named by the canonical path of its jarfile, so that a directory reached through a symlink or -- on
            // Windows -- through an 8.3 short name is recognized as the directory it really is, and read once
            absoluteDir = FileUtils.canonicalize(absoluteDir);
        } catch (final IOException | SecurityException e) {
            // A directory whose canonical path cannot be found is named by the path it was reached through, rather
            // than failing to open a directory that can be read perfectly well
        }
        this.dir = absoluteDir;
        try {
            this.pathStr = FileUtils.pathStr(this.dir);
        } catch (final IOError | SecurityException e) {
            throw new IOException("Could not form URI for " + this.dir + " : " + e, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    String fileStoreType() {
        return "directory";
    }

    @Override
    public String getPath() {
        return pathStr;
    }

    @Override
    public URI getURI() {
        return URLPaths.toURI(dir);
    }

    @Override
    public URI resolveURI(final String pathWithinRoot) {
        return URLPaths.toURI(dir.resolve(pathWithinRoot));
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
    void walkImpl(final VfsVisitor visitor, final @Nullable LogNode log) throws IOException {
        // Symlinks can make a directory tree cyclic, so record which directories have already been walked, by their
        // canonical path -- otherwise a directory that contains a symlink to one of its own ancestors makes this
        // recursion run until it runs out of stack
        walkRecursively(dir, "", visitor, new HashSet<>(), log);
    }

    @Override
    List<VfsEntry> getEntriesImpl() throws IOException {
        var entriesCurr = entries;
        if (entriesCurr == null) {
            final List<VfsEntry> entriesTmp = new ArrayList<>();
            walk(collectingVisitor(entriesTmp));
            entriesCurr = Collections.unmodifiableList(entriesTmp);
            // Two threads racing here each list the directory, and both get an equivalent list back, which is
            // harmless -- the entries hold no resources
            entries = entriesCurr;
        }
        return entriesCurr;
    }

    /**
     * Walk the files under a directory, recursing into its subdirectories.
     *
     * @param currDir
     *            the directory to walk.
     * @param namePrefix
     *            the name of {@code currDir} relative to the root, with a trailing {@code '/'}, or the empty string
     *            for the root itself.
     * @param visitor
     *            the visitor to hand the entries to.
     * @param visitedDirs
     *            the canonical paths of the directories that have already been walked.
     * @param log
     *            the log node, or null to not log.
     * @return true to go on walking, or false if the visitor asked for the walk to stop.
     * @throws IOException
     *             if the directory could not be listed.
     */
    private boolean walkRecursively(final Path currDir, final String namePrefix, final VfsVisitor visitor,
            final Set<Path> visitedDirs, final @Nullable LogNode log) throws IOException {
        // Ask before listing, since not listing an unwanted directory is the whole point of asking
        if (!visitor.enterDirectory(namePrefix.isEmpty() ? "/" : namePrefix)) {
            return true;
        }
        final Path canonicalDir;
        try {
            canonicalDir = currDir.toRealPath();
        } catch (final IOException | SecurityException e) {
            // A directory that cannot be resolved is skipped, rather than aborting the whole listing
            if (log != null) {
                log.log("Could not canonicalize path: " + currDir + " : " + e);
            }
            return true;
        }
        if (!visitedDirs.add(canonicalDir)) {
            if (log != null) {
                log.log("Reached symlink cycle, stopping recursion: " + currDir);
            }
            return true;
        }
        final List<Path> children = new ArrayList<>();
        try (var dirStream = Files.newDirectoryStream(currDir)) {
            for (final Path child : dirStream) {
                children.add(child);
            }
        } catch (final IOException | SecurityException e) {
            // A directory that cannot be opened is skipped, rather than aborting the whole listing
            if (log != null) {
                log.log("Could not read directory " + currDir + " : " + e.getMessage());
            }
            return true;
        }
        // List the entries of a directory in a deterministic order, since the order a filesystem returns them in is
        // not specified. Compare the filenames rather than the Paths, since Path#compareTo is case-insensitive on
        // Windows, which would order the same set of files differently there.
        CollectionUtils.sortIfNotEmpty(children,
                Comparator.comparing(child -> String.valueOf(child.getFileName())));
        // Visit the files of a directory before recursing into its subdirectories, so that the reads that follow
        // the walk are grouped the same way the filesystem groups the metadata they need
        final List<Path> subDirs = new ArrayList<>();
        for (final Path child : children) {
            // Read the attributes of each child once, both to tell the files from the subdirectories and to hand
            // to the entry, so that a walk costs one metadata read per child
            final var attributes = FileUtils.readAttributes(child);
            if (attributes.isDirectory()) {
                subDirs.add(child);
            } else if (attributes.isRegularFile() && !visitor
                    .visitEntry(new DirEntry(this, child, namePrefix + child.getFileName(), attributes))) {
                return false;
            }
        }
        for (final Path subDir : subDirs) {
            if (!walkRecursively(subDir, namePrefix + subDir.getFileName() + "/", visitor, visitedDirs, log)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the name of a file under this root, relative to the root, with {@code '/'} as the separator, which is
     * the form of the names a walk of this root reports.
     *
     * @param file
     *            the file, which must be under this root's directory.
     * @return the name of the file relative to the root.
     */
    private String relativeName(final Path file) {
        // Joining the segments, rather than replacing the separator in the string form of the path, is what makes
        // this independent of the separator of the filesystem the directory is in
        final var relativeName = new StringBuilder();
        for (final Path segment : dir.relativize(file)) {
            if (relativeName.length() > 0) {
                relativeName.append('/');
            }
            relativeName.append(segment);
        }
        return relativeName.toString();
    }

    @Override
    @Nullable
    VfsEntry getEntryImpl(final String name) throws IOException {
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
        if (!resolved.startsWith(dir) || !FileUtils.canReadAndIsFile(resolved)) {
            return null;
        }
        // The name is matched exactly, whatever kind of root this is, so a name that reaches the file by some other
        // route is not a match: one with "." or ".." segments in it, or, on Windows, one written with the platform
        // separator rather than '/'. Path#resolve accepts all of those, whereas the same name would not be found in
        // an archive root, where the name is looked up in a map of the names the entries are stored under
        if (!name.equals(relativeName(resolved))) {
            return null;
        }
        // The filesystems of Windows and macOS answer a lookup for a name whose case does not match the name the
        // file is stored under, and this method matches names exactly, so such a match is not one
        if (isCaseFoldedMatch(dir, resolved)) {
            return null;
        }
        return new DirEntry(this, resolved, name, /* attributes = */ null);
    }
}
