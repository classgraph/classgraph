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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/** A filesystem directory root. */
public final class DirRoot extends VfsRoot {
    /**
     * A step in the iterative directory traversal stack.
     *
     * @param path
     *            the directory to enter, or the canonical ancestor path to remove on exit.
     * @param namePrefix
     *            the root-relative directory prefix ending with {@code '/'}, or {@code ""} for the root or an exit
     *            step.
     * @param exiting
     *            {@code true} if exiting a directory after visiting its children.
     */
    private record WalkStep(Path path, String namePrefix, boolean exiting) {
    }

    /** The canonicalized directory path. */
    private final Path dir;

    /** The normalized directory path string using {@code '/'} separators. */
    private final String pathStr;

    /** Cached list of entries under this directory, initialized on first call to {@link #getEntries()}. */
    private volatile @Nullable List<VfsEntry> entries;

    /**
     * Constructor.
     *
     * @param vfs
     *            the owning {@link Vfs}.
     * @param dir
     *            the directory path.
     * @throws IOException
     *             if the directory does not exist or cannot be read.
     */
    DirRoot(final Vfs vfs, final Path dir) throws IOException {
        super(vfs);
        Path absoluteDir;
        try {
            absoluteDir = dir.toAbsolutePath().normalize();
        } catch (final IOError e) {
            throw new IOException("Could not resolve directory " + dir + " : " + e, e);
        }
        if (!FileUtils.canReadAndIsDir(absoluteDir)) {
            throw new IOException("Not a readable directory: " + absoluteDir);
        }
        // A root is named by the canonical path of the directory that backs it, the same way a jarfile root is
        // named by the canonical path of its jarfile, so that a directory reached through a symlink or -- on
        // Windows -- through an 8.3 short name is recognized as the directory it really is, and read once
        this.dir = FileUtils.canonicalize(absoluteDir);
        try {
            this.pathStr = FileUtils.pathStr(this.dir);
        } catch (final IOError e) {
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
        } catch (final UnsupportedOperationException _) {
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
        // Symlinks can make a directory tree cyclic, so record the canonical paths of the directories on the way
        // down to the one being walked -- otherwise a directory that contains a symlink to one of its own ancestors
        // makes the walk never finish. Only those ancestors are recorded, not every directory walked so far, so
        // that a directory reached a second time by another path that is not a cycle, such as a symlink to a sibling
        // directory, is walked again under that path, as Files.walk does with FOLLOW_LINKS.
        walkIteratively(visitor, log);
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
     * Iteratively walk the files under this directory root.
     *
     * @param visitor
     *            the visitor to notify of directories and entries.
     * @param log
     *            the log node, or {@code null}.
     * @return {@code true} if traversal completed, or {@code false} if stopped by the visitor.
     */
    private boolean walkIteratively(final VfsVisitor visitor, final @Nullable LogNode log) {
        final Set<Path> visitedDirs = new HashSet<>();
        final Deque<WalkStep> pending = new ArrayDeque<>();
        pending.push(new WalkStep(dir, "", false));
        while (!pending.isEmpty()) {
            final var step = pending.pop();
            if (step.exiting) {
                visitedDirs.remove(step.path);
                continue;
            }
            final var currDir = step.path;
            final var namePrefix = step.namePrefix;
            // Ask before listing, since not listing an unwanted directory is the whole point of asking
            if (!visitor.enterDirectory(namePrefix.isEmpty() ? "/" : namePrefix)) {
                continue;
            }
            final Path canonicalDir;
            try {
                canonicalDir = currDir.toRealPath();
            } catch (final IOException e) {
                // A directory that cannot be resolved is skipped, rather than aborting the whole listing
                if (log != null) {
                    log.log("Could not canonicalize path: " + currDir + " : " + e);
                }
                continue;
            }
            if (!visitedDirs.add(canonicalDir)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping traversal: " + currDir);
                }
                continue;
            }
            // walkChildren pushes the subdirectories on top of this step, so the directory stays among the recorded
            // ancestors until all of them have been walked
            pending.push(new WalkStep(canonicalDir, "", true));
            if (!walkChildren(currDir, namePrefix, visitor, pending, log)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Visit the files in a directory and push its subdirectories onto the traversal stack.
     *
     * @param currDir
     *            the directory to list.
     * @param namePrefix
     *            the root-relative prefix of {@code currDir} with a trailing {@code '/'}, or {@code ""} for the
     *            root.
     * @param visitor
     *            the visitor to notify of entries.
     * @param pending
     *            the traversal stack.
     * @param log
     *            the log node, or {@code null}.
     * @return {@code true} to continue traversal, or {@code false} if stopped by the visitor.
     */
    private boolean walkChildren(final Path currDir, final String namePrefix, final VfsVisitor visitor,
            final Deque<WalkStep> pending, final @Nullable LogNode log) {
        final List<Path> children = new ArrayList<>();
        try (var dirStream = Files.newDirectoryStream(currDir)) {
            for (final Path child : dirStream) {
                children.add(child);
                // Each entry listed counts as progress, so that the threads waiting for this one do not time out
                // while a large directory on a slow filesystem is still being listed
                getVfs().interruptionChecker().progress();
            }
        } catch (final IOException e) {
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
        // Visit the files of a directory before walking its subdirectories, so that the reads that follow
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
        // Push in reverse, so that the subdirectories are walked in sorted order
        for (var i = subDirs.size() - 1; i >= 0; i--) {
            final var subDir = subDirs.get(i);
            pending.push(new WalkStep(subDir, namePrefix + subDir.getFileName() + "/", false));
        }
        return true;
    }

    /**
     * Return the path of {@code file} relative to {@link #dir}, using {@code '/'} as the separator.
     *
     * @param file
     *            a path under {@link #dir}.
     * @return the root-relative path string.
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
        final var resolved = pathForNio(name);
        return resolved != null && FileUtils.canReadAndIsFile(resolved)
                ? new DirEntry(this, resolved, name, /* attributes = */ null)
                : null;
    }

    /**
     * Resolve an exact, case-sensitive root-relative path to a filesystem {@link Path}.
     *
     * @param name
     *            the root-relative path, or {@code ""} for the root itself.
     * @return the resolved {@link Path}, or {@code null} if invalid, outside the root, or mismatched in case.
     * @throws IOException
     *             if checking case-sensitive path matching fails.
     */
    @Nullable
    Path pathForNio(final String name) throws IOException {
        final Path resolved;
        try {
            resolved = dir.resolve(name).normalize();
        } catch (final InvalidPathException _) {
            // A name that is not a legal path on this filesystem names nothing
            return null;
        }
        // A name containing ".." must not be able to reach outside the root, and an absolute name must not be able
        // to replace it -- Path#resolve returns the argument unchanged if it is absolute
        if (!resolved.startsWith(dir)) {
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
        // Another VFS view already enforces exact names. Rechecking its existence and real path here would
        // recursively duplicate those lookups at every wrapper level instead of delegating once.
        // The provider name also recognizes copies loaded by plugins in other classloaders: their namespace has
        // the same exact-match contract, even though their VfsPath is not an instance of this loader's class.
        final boolean classGraphView = resolved instanceof VfsPath || VfsFileSystemProvider.class.getName()
                .equals(resolved.getFileSystem().provider().getClass().getName());
        if (!classGraphView && Files.exists(resolved) && isCaseFoldedMatch(dir, resolved)) {
            return null;
        }
        return resolved;
    }

    /**
     * Look up a regular file entry by root-relative path for a NIO view, including unreadable files.
     *
     * @param name
     *            the root-relative path.
     * @return the file entry, or {@code null} if not a regular file.
     * @throws IOException
     *             if checking case-sensitive path matching fails.
     */
    @Nullable
    VfsEntry fileEntryForNio(final String name) throws IOException {
        final var path = pathForNio(name);
        if (path == null) {
            return null;
        }
        final var attributes = FileUtils.readAttributes(path);
        return attributes.isRegularFile() ? new DirEntry(this, path, name, attributes) : null;
    }
}
