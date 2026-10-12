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

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.internal.StoragePaths;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** A directory in a filesystem. */
public final class DirRoot extends VfsRoot {
    /**
     * A directory to enter, or a canonical ancestor to remove after its children have been visited.
     *
     * @param path
     *            the directory to enter, or the canonical ancestor to remove for an exit step.
     * @param namePrefix
     *            the root-relative directory name with a trailing slash, or empty for the root or an exit step.
     * @param exiting
     *            whether this step removes an ancestor after its children have been visited.
     */
    private record WalkStep(Path path, String namePrefix, boolean exiting) {
    }

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
        // A root is named by the canonical path of the directory that backs it, the same way a jarfile root is
        // named by the canonical path of its jarfile, so that a directory reached through a symlink or -- on
        // Windows -- through an 8.3 short name is recognized as the directory it really is, and read once
        this.dir = FileUtils.canonicalize(absoluteDir);
        try {
            this.pathStr = StoragePaths.pathStr(this.dir);
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
        return StoragePaths.toURI(dir);
    }

    @Override
    public URI resolveURI(final String pathWithinRoot) {
        return StoragePaths.toURI(dir.resolve(pathWithinRoot));
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
        // Symlinks can make a directory tree cyclic, so record the canonical paths of the directories on the way
        // down to the one being walked -- otherwise a directory that contains a symlink to one of its own ancestors
        // makes the traversal never finish. Only those ancestors are recorded, not every
        // directory walked so far, so that a directory reached a second time by another path that is not a cycle,
        // such as a symlink to a sibling directory, is walked again under that path, as Files.walk does with
        // FOLLOW_LINKS.
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
     * Walk the files under this root without consuming call-stack space for each directory level.
     *
     * @param visitor
     *            the visitor to hand the entries to.
     * @param log
     *            the log node, or null to not log.
     * @return true to go on walking, or false if the visitor asked for the walk to stop.
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
            // Ask before listing, since not listing an unwanted directory is the whole point of asking.
            if (!visitor.enterDirectory(namePrefix.isEmpty() ? "/" : namePrefix)) {
                continue;
            }
            final Path canonicalDir;
            try {
                canonicalDir = currDir.toRealPath();
            } catch (final IOException | SecurityException e) {
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
            pending.push(new WalkStep(canonicalDir, "", true));
            if (!walkChildren(currDir, namePrefix, visitor, pending, log)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walk the files directly in a directory, then schedule its subdirectories in traversal order.
     *
     * @param currDir
     *            the directory to walk.
     * @param namePrefix
     *            the name of {@code currDir} relative to the root, with a trailing {@code '/'}, or the empty string
     *            for the root itself.
     * @param visitor
     *            the visitor to hand the entries to.
     * @param pending
     *            the traversal stack, with the directory's exit step already queued.
     * @param log
     *            the log node, or null to not log.
     * @return true to go on walking, or false if the visitor asked for the walk to stop.
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
        for (var i = subDirs.size() - 1; i >= 0; i--) {
            final var subDir = subDirs.get(i);
            pending.push(new WalkStep(subDir, namePrefix + subDir.getFileName() + "/", false));
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
        final var resolved = pathForNio(name);
        return resolved != null && FileUtils.canReadAndIsFile(resolved)
                ? new DirEntry(this, resolved, name, /* attributes = */ null)
                : null;
    }

    /**
     * Resolve an exact root-relative name without enumerating the directory or requiring read access.
     *
     * @param name
     *            the root-relative name, or empty for the root itself.
     * @return the native path, or null for a noncanonical or mismatched name.
     * @throws IOException
     *             if case-sensitive matching fails.
     */
    @Nullable
    Path pathForNio(final String name) throws IOException {
        final Path resolved;
        try {
            resolved = dir.resolve(name).normalize();
        } catch (final InvalidPathException e) {
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
        if (!(resolved instanceof VfsPath) && Files.exists(resolved) && isCaseFoldedMatch(dir, resolved)) {
            return null;
        }
        return resolved;
    }

    /**
     * Look up one file for a NIO view, including an unreadable file that still exists.
     *
     * @param name
     *            the root-relative name.
     * @return the file entry, or null if the name is not a regular file.
     * @throws IOException
     *             if case-sensitive matching fails.
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
