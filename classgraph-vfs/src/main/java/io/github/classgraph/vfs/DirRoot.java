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
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.vfs.internal.slice.Slice;

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

    /**
     * The string form of {@link #dir} followed by {@code '/'}, if {@link #dir} is in the default filesystem and
     * that filesystem's separator is {@code '/'}, otherwise null. See {@link #isExactName}.
     */
    private final @Nullable String dirPrefix;

    /**
     * The string form of {@link #dir} followed by {@code '\'}, if {@link #dir} is in the default filesystem and
     * that filesystem's separator is {@code '\'}, as it is on Windows, otherwise null. See {@link #isExactName}.
     */
    private final @Nullable String backslashDirPrefix;

    /** Cached list of entries under this directory, initialized on first call to {@link #getEntries()}. */
    private volatile @Nullable List<VfsEntry> entries;

    /**
     * The entries that a walk listed or a lookup found, by root-relative path, so that looking a file up again
     * reads no metadata. An entry holds the file's path and metadata, never its content. Names that were not found
     * are not recorded, so a file created after the root was listed is still found.
     */
    private final Map<String, DirEntry> entriesByName = new ConcurrentHashMap<>();

    /**
     * The most directory handles (see {@link #dirHandles}) that the directory roots of this process keep open
     * together. Each handle holds a file descriptor, and a program may open many directory roots, so the limit is
     * on all of them together, to keep well inside the process's limit on open files. A handle is held on a
     * directory, not on a file, so the limit is on the number of directories, however many files each holds. When a
     * handle is needed and this many are open, the one that was opened first is closed (see
     * {@link #openDirHandles}).
     */
    private static final int MAX_DIR_HANDLES = 256;

    /** The number of directory handles that the directory roots of this process hold open. */
    private static final AtomicInteger numOpenDirHandles = new AtomicInteger();

    /**
     * The directory handles that the directory roots of this process hold open, in the order they were opened. When
     * {@link #MAX_DIR_HANDLES} are open, the first is closed to make room for a new one, since a walk from the top
     * down, which is how a scan reads a directory tree, is done with the directory it opened first.
     */
    private static final Queue<DirHandle> openDirHandles = new ConcurrentLinkedQueue<>();

    /** An open handle on a directory of a root. */
    static final class DirHandle {
        /** The root. */
        private final DirRoot root;

        /** The root-relative path of the directory, or {@code ""} for the root itself. */
        private final String dirName;

        /** The handle. */
        private final SecureDirectoryStream<Path> stream;

        /**
         * Constructor.
         *
         * @param root
         *            the root.
         * @param dirName
         *            the root-relative path of the directory, or {@code ""} for the root itself.
         * @param stream
         *            the handle.
         */
        DirHandle(final DirRoot root, final String dirName, final SecureDirectoryStream<Path> stream) {
            this.root = root;
            this.dirName = dirName;
            this.stream = stream;
        }

        /**
         * Return the root.
         *
         * @return the root.
         */
        DirRoot root() {
            return root;
        }

        /**
         * Return the root-relative path of the directory.
         *
         * @return the root-relative path of the directory, or {@code ""} for the root itself.
         */
        String dirName() {
            return dirName;
        }

        /**
         * Return the handle.
         *
         * @return the handle.
         */
        SecureDirectoryStream<Path> stream() {
            return stream;
        }
    }

    /**
     * Whether files are looked up and opened relative to a handle on their directory (see {@link #dirHandles}),
     * which is only possible on the default filesystem, and not on Windows, whose JDK has no
     * {@link SecureDirectoryStream}.
     */
    private final boolean useDirHandles;

    /**
     * Open handles on the directories that files have been looked up or opened in, by root-relative path
     * ({@code ""} for the root). Reading a file's metadata or opening it relative to a handle on its directory
     * resolves only the file's own name, rather than every segment of its path, which makes each of these system
     * calls cheaper. A name that is not found through a handle is looked up again by its whole path, so that a
     * directory that has been deleted and recreated since its handle was opened is still read.
     */
    private final Map<String, DirHandle> dirHandles = new ConcurrentHashMap<>();

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
        final var dirStr = this.dir.toString();
        this.dirPrefix = this.dir.getFileSystem() == FileSystems.getDefault()
                && "/".equals(this.dir.getFileSystem().getSeparator())
                        ? dirStr.endsWith("/") ? dirStr : dirStr + "/"
                        : null;
        this.backslashDirPrefix = this.dir.getFileSystem() == FileSystems.getDefault()
                && "\\".equals(this.dir.getFileSystem().getSeparator())
                        ? dirStr.endsWith("\\") ? dirStr : dirStr + "\\"
                        : null;
        this.useDirHandles = dirPrefix != null;
    }

    @Override
    void releaseResources(final @Nullable LogNode log) {
        for (final var dirName : new ArrayList<>(dirHandles.keySet())) {
            final var handle = dirHandles.remove(dirName);
            if (handle != null) {
                closeDirHandle(handle);
            }
        }
    }

    /**
     * Close a directory handle that has been taken out of {@link #dirHandles}, or was never put in it. A thread
     * that is using the handle when it is closed gets {@link ClosedDirectoryStreamException}, and reads the file by
     * its whole path instead.
     *
     * @param handle
     *            the handle.
     */
    private static void closeDirHandle(final DirHandle handle) {
        try {
            handle.stream().close();
        } catch (final IOException _) {
            // Nothing was written through the handle, so there is nothing to lose
        }
        openDirHandles.remove(handle);
        numOpenDirHandles.decrementAndGet();
    }

    /**
     * Take a place under {@link #MAX_DIR_HANDLES} for a handle about to be opened, closing the handle that was
     * opened first if there is no place free. The place is taken before the handle is opened, so that threads
     * opening handles at once cannot go over the limit together, and every handle opened gives its place back when
     * {@link #closeDirHandle(DirHandle)} closes it.
     *
     * @return true if a place was taken, or false if none could be freed, when another thread is closing the handle
     *         that was opened first, or this process's directory roots hold no handle to close.
     */
    private static boolean takeDirHandlePlace() {
        if (numOpenDirHandles.incrementAndGet() <= MAX_DIR_HANDLES) {
            return true;
        }
        numOpenDirHandles.decrementAndGet();
        final var first = openDirHandles.poll();
        if (first == null || !first.root().dirHandles.remove(first.dirName(), first)) {
            return false;
        }
        closeDirHandle(first);
        if (numOpenDirHandles.incrementAndGet() <= MAX_DIR_HANDLES) {
            return true;
        }
        // Another thread took the place first
        numOpenDirHandles.decrementAndGet();
        return false;
    }

    /**
     * Return the number of directory handles that the directory roots of this process hold open.
     *
     * @return the number of handles.
     */
    static int numOpenDirHandles() {
        return numOpenDirHandles.get();
    }

    /**
     * Return the number of directory handles that this root holds open.
     *
     * @return the number of handles.
     */
    int numDirHandles() {
        return dirHandles.size();
    }

    /**
     * Return the open handle on a directory of this root, opening it if it is not yet open.
     *
     * @param dirName
     *            the root-relative path of the directory, or {@code ""} for the root itself.
     * @return the handle, or null if files are not read through directory handles, or the handle could not be
     *         opened, or no place could be freed under {@link #MAX_DIR_HANDLES} for it.
     * @throws NoSuchFileException
     *             if the directory does not exist, so that no file can be found in it.
     * @throws NotDirectoryException
     *             if the path of the directory names something that is not a directory.
     */
    private @Nullable DirHandle dirHandle(final String dirName) throws NoSuchFileException, NotDirectoryException {
        if (!useDirHandles) {
            return null;
        }
        final var known = dirHandles.get(dirName);
        if (known != null) {
            return known;
        }
        if (!takeDirHandlePlace()) {
            return null;
        }
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(dirName.isEmpty() ? dir : dir.resolve(dirName));
        } catch (final NoSuchFileException | NotDirectoryException e) {
            throw e;
        } catch (final IOException | SecurityException _) {
            return null;
        } finally {
            if (stream == null) {
                numOpenDirHandles.decrementAndGet();
            }
        }
        if (!(stream instanceof final SecureDirectoryStream<Path> secureStream)) {
            try {
                stream.close();
            } catch (final IOException _) {
                // Nothing was read through the stream
            }
            numOpenDirHandles.decrementAndGet();
            return null;
        }
        final var handle = new DirHandle(this, dirName, secureStream);
        // Queued before it is added to this root, so that a close of this root, which takes the handle out of the
        // queue, cannot run between the two and leave the handle queued
        openDirHandles.add(handle);
        final var raced = dirHandles.putIfAbsent(dirName, handle);
        if (raced != null) {
            closeDirHandle(handle);
            return raced;
        }
        // A handle added after the close of this root released the handles would never be closed, so check for a
        // close only after adding it
        if (isClosed() && dirHandles.remove(dirName, handle)) {
            closeDirHandle(handle);
            return null;
        }
        return handle;
    }

    /**
     * Read the metadata of a file, relative to the handle on its directory if there is one.
     *
     * @param dirName
     *            the root-relative path of the file's directory, or {@code ""} for the root itself.
     * @param handle
     *            the handle on the file's directory, or null if there is none.
     * @param fileName
     *            the name of the file within its directory, if {@code handle} is not null.
     * @param name
     *            the root-relative path of the file.
     * @param path
     *            the path that {@link #resolveExactName(String)} resolved the file's name to, or null if it was not
     *            resolved (see {@link #resolveCanonicalName(String)}).
     * @return the metadata, or null if the file does not exist or its metadata could not be read.
     */
    private @Nullable BasicFileAttributes readAttributes(final String dirName, final @Nullable DirHandle handle,
            final @Nullable Path fileName, final String name, final @Nullable Path path) {
        if (handle != null && fileName != null) {
            try {
                // The POSIX view, since the basic view of a SecureDirectoryStream wraps the attributes it reads in
                // an object that does not give the POSIX permissions
                return handle.stream().getFileAttributeView(fileName, PosixFileAttributeView.class)
                        .readAttributes();
            } catch (final IOException | ClosedDirectoryStreamException _) {
                // The file is not in the directory the handle is open on, which may have been replaced since the
                // handle was opened, or the handle was closed, so look the file up by its whole path
            }
        }
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path != null ? path : resolveCanonicalName(name),
                    BasicFileAttributes.class);
        } catch (final IOException _) {
            // The file is missing, or its metadata cannot be read. FileUtils#readAttributes is not used here, since
            // on this failure it falls back to File#isFile, a second metadata read that fails the same way
            return null;
        }
        if (handle != null && dirHandles.remove(dirName, handle)) {
            // The file was found by its whole path but not through the handle, so the handle is open on a
            // directory that is no longer there, and is replaced by the next lookup in the directory
            closeDirHandle(handle);
        }
        return attributes;
    }

    /**
     * Open a file of this root for reading, relative to the handle on its directory if there is one.
     *
     * @param name
     *            the root-relative path of the file.
     * @param path
     *            the path of the file, or null if it has not been built (see
     *            {@link #resolveCanonicalName(String)}).
     * @param entryHandle
     *            the handle on the file's directory that the file's entry was found through, or null. It may have
     *            been closed since, in which case the handle that is open on the directory now is used instead.
     * @param fileName
     *            the name of the file within its directory, if {@code entryHandle} is not null.
     * @return the channel, which the caller must close.
     * @throws IOException
     *             if the file could not be opened.
     */
    FileChannel openFile(final String name, final @Nullable Path path, final @Nullable DirHandle entryHandle,
            final @Nullable Path fileName) throws IOException {
        if (entryHandle != null && fileName != null) {
            final var channel = openFile(entryHandle, fileName);
            if (channel != null) {
                return channel;
            }
        }
        final var resolvedPath = path != null ? path : resolveCanonicalName(name);
        final var lastSlashIdx = name.lastIndexOf('/');
        final var handle = dirHandle(lastSlashIdx < 0 ? "" : name.substring(0, lastSlashIdx));
        if (handle != null && handle != entryHandle) {
            final var channel = openFile(handle, resolvedPath.getFileName());
            if (channel != null) {
                return channel;
            }
        }
        // Open the file by its whole path, which throws the exception to report if it fails again
        return FileChannel.open(resolvedPath, StandardOpenOption.READ);
    }

    /**
     * Open a file for reading relative to a handle on its directory.
     *
     * @param handle
     *            the handle.
     * @param fileName
     *            the name of the file within the directory.
     * @return the channel, which the caller must close, or null if the file could not be opened through the handle.
     */
    private static @Nullable FileChannel openFile(final DirHandle handle, final Path fileName) {
        try {
            final var channel = handle.stream().newByteChannel(fileName, Set.of(StandardOpenOption.READ));
            if (channel instanceof final FileChannel fileChannel) {
                return fileChannel;
            }
            channel.close();
        } catch (final IOException | ClosedDirectoryStreamException _) {
            // The file is not in the directory the handle is open on, or the handle was closed
        }
        return null;
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
        final List<DirListing.Child> children;
        try {
            // Each entry listed counts as progress, so that the threads waiting for this one do not time out while
            // a large directory on a slow filesystem is still being listed
            children = DirListing.list(currDir, getVfs().interruptionChecker()::progress);
        } catch (final IOException | DirectoryIteratorException e) {
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
                Comparator.comparing(child -> String.valueOf(child.path().getFileName())));
        // Visit the files of a directory before walking its subdirectories, so that the reads that follow
        // the walk are grouped the same way the filesystem groups the metadata they need
        final List<Path> subDirs = new ArrayList<>();
        for (final var listed : children) {
            // The attributes of each child both tell the files from the subdirectories and are handed to the
            // entry, so that a walk reads the metadata of each child at most once
            final var child = listed.path();
            final var attributes = listed.attributes();
            if (attributes.isDirectory()) {
                subDirs.add(child);
            } else if (attributes.isRegularFile()) {
                final var name = namePrefix + child.getFileName();
                final var entry = new DirEntry(this, child, name, attributes);
                entriesByName.put(name, entry);
                if (!visitor.visitEntry(entry)) {
                    return false;
                }
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

    /**
     * Returns whether {@code name} is exactly the root-relative path of {@code resolved}, the normalized result of
     * resolving {@code name} against {@link #dir}. On the default filesystem with {@code '/'} as its separator,
     * parsing and normalizing a path removes repeated and trailing separators and {@code "."} and {@code ".."}
     * segments, so the name is exact if and only if the string form of {@code resolved} is {@link #dirPrefix}
     * followed by {@code name}. Comparing strings this way costs a fraction of {@link #relativeName}, which
     * relativizes the path and joins its segments. On Windows, parsing turns each {@code '/'} into {@code '\'}, so
     * a name holding no {@code '\'} is exact if and only if the string form of {@code resolved} is
     * {@link #backslashDirPrefix} followed by {@code name} with {@code '\'} in place of each {@code '/'}, and a
     * name holding a {@code '\'} is never exact, since {@code '\'} is a separator there.
     *
     * @param name
     *            the root-relative path that was looked up.
     * @param resolved
     *            {@code name} resolved against {@link #dir} and normalized, already checked to be under it.
     * @return true if {@code name} is the exact root-relative path of {@code resolved}.
     */
    private boolean isExactName(final String name, final Path resolved) {
        if (dirPrefix == null) {
            if (backslashDirPrefix == null || name.isEmpty()) {
                return name.equals(relativeName(resolved));
            }
            final var resolvedStr = resolved.toString();
            return name.indexOf('\\') < 0 && resolvedStr.length() == backslashDirPrefix.length() + name.length()
                    && resolvedStr.startsWith(backslashDirPrefix) && resolvedStr
                            .regionMatches(backslashDirPrefix.length(), name.replace('/', '\\'), 0, name.length());
        }
        if (name.isEmpty()) {
            // The root itself
            return resolved.equals(dir);
        }
        final var resolvedStr = resolved.toString();
        return resolvedStr.length() == dirPrefix.length() + name.length() && resolvedStr.startsWith(dirPrefix)
                && resolvedStr.endsWith(name);
    }

    @Override
    @Nullable
    VfsEntry getEntryImpl(final String name) throws IOException {
        if (name.isEmpty()) {
            return null;
        }
        final var known = entriesByName.get(name);
        if (known != null) {
            return known;
        }
        // Whether the file can be read is not checked, since reading it throws if it cannot, and a check here would
        // cost a second syscall on every lookup
        final DirEntry entry;
        final var lastSlashIdx = dirPrefix == null ? NOT_CANONICAL : lastSlashIdxIfCanonical(name);
        if (lastSlashIdx != NOT_CANONICAL) {
            // The name resolves to dirPrefix followed by the name (see resolveExactName), so the file is looked up
            // through the handle on its directory without building its path, which is only built if it is needed
            entry = fileEntry(name, lastSlashIdx, null);
        } else {
            final var path = resolveExactName(name);
            entry = path == null ? null : fileEntry(name, name.lastIndexOf('/'), path);
        }
        if (entry != null) {
            entriesByName.put(name, entry);
        }
        return entry;
    }

    @Override
    byte @Nullable [] loadImpl(final String name) throws IOException {
        if (name.isEmpty()) {
            return null;
        }
        final var lastSlashIdx = dirPrefix == null ? NOT_CANONICAL : lastSlashIdxIfCanonical(name);
        final byte[] content;
        if (lastSlashIdx != NOT_CANONICAL) {
            final var dirName = lastSlashIdx < 0 ? "" : name.substring(0, lastSlashIdx);
            final DirHandle handle;
            try {
                handle = dirHandle(dirName);
            } catch (final NoSuchFileException | NotDirectoryException _) {
                // The file's directory does not exist, so neither does the file
                return null;
            }
            content = handle != null ? loadThroughHandle(name, lastSlashIdx, dirName, handle)
                    : loadByPath(name, resolveCanonicalName(name));
        } else {
            final var path = resolveExactName(name);
            if (path == null) {
                return null;
            }
            content = loadByPath(name, path);
        }
        return content != NOT_LOADED ? content : super.loadImpl(name);
    }

    /** The value of {@link #loadThroughHandle} when the file has to be read by looking up its entry instead. */
    private static final byte[] NOT_LOADED = new byte[0];

    /**
     * Read a file through the handle on its directory without first reading its metadata, so that the open finds
     * out whether the file exists, and the length of the array is read from the open file.
     *
     * @param name
     *            the root-relative path of the file, in canonical form.
     * @param lastSlashIdx
     *            the index of the last {@code '/'} in {@code name}, or -1 if it has none.
     * @param dirName
     *            the root-relative path of the file's directory, or {@code ""} for the root itself.
     * @param handle
     *            the handle on the file's directory.
     * @return the content of the file, null if the file does not exist or is a directory, or {@link #NOT_LOADED} if
     *         the file has to be read by looking up its entry instead.
     * @throws IOException
     *             if the file could not be read.
     */
    private byte @Nullable [] loadThroughHandle(final String name, final int lastSlashIdx, final String dirName,
            final DirHandle handle) throws IOException {
        final var channel = openFile(handle, dir.getFileSystem().getPath(name.substring(lastSlashIdx + 1)));
        if (channel != null) {
            return read(channel, name, null);
        }
        // The file is not in the directory, or the handle was closed, or the directory was replaced since the handle
        // was opened on it, so the file is opened by its whole path to tell these apart
        final var path = resolveCanonicalName(name);
        final FileChannel channelByPath;
        try {
            channelByPath = FileChannel.open(path, StandardOpenOption.READ);
        } catch (final NoSuchFileException _) {
            return null;
        } catch (final IOException _) {
            // Reported by the lookup of the entry
            return NOT_LOADED;
        }
        // The file is there, so it was not found through the handle because the handle is open on a directory that
        // is no longer there, and is replaced by the next lookup in the directory
        if (dirHandles.remove(dirName, handle)) {
            closeDirHandle(handle);
        }
        return read(channelByPath, name, path);
    }

    /**
     * Read a file by its whole path without first reading its metadata, so that the open finds out whether the file
     * exists, and the length of the array is read from the open file.
     *
     * @param name
     *            the root-relative path of the file.
     * @param path
     *            the path of the file.
     * @return the content of the file, null if the file does not exist or is a directory, or {@link #NOT_LOADED} if
     *         the file has to be read by looking up its entry instead.
     * @throws IOException
     *             if the file could not be read.
     */
    private byte @Nullable [] loadByPath(final String name, final Path path) throws IOException {
        final FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (final NoSuchFileException _) {
            return null;
        } catch (final IOException _) {
            // Windows does not open a directory as a file, and the lookup of the entry tells a directory, which is
            // not a file entry, from a file that cannot be opened, which it reports
            return NOT_LOADED;
        }
        return read(channel, name, path);
    }

    /**
     * Read and close a file opened by {@link #loadThroughHandle} or {@link #loadByPath}.
     *
     * @param channel
     *            the open file.
     * @param name
     *            the root-relative path of the file.
     * @param path
     *            the path of the file, or null if it has not been built (see {@link #resolveCanonicalName}).
     * @return the content of the file, null if the file is a directory, or {@link #NOT_LOADED} if the file has to
     *         be read by looking up its entry instead.
     * @throws IOException
     *             if the file could not be read.
     */
    private byte @Nullable [] read(final FileChannel channel, final String name, final @Nullable Path path)
            throws IOException {
        try (channel) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException(
                        "Interrupted while reading " + (path != null ? path : resolveCanonicalName(name)));
            }
            final var length = channel.size();
            if (length > Slice.MAX_BUFFER_SIZE) {
                // Too large for an array, which the lookup of the entry reports
                return NOT_LOADED;
            }
            final var content = DirEntry.read(channel, (int) length);
            // A file whose length changed while it was read is read again by looking up its entry
            return content != null ? content : NOT_LOADED;
        } catch (final ClosedChannelException e) {
            // Closed by an interrupt
            throw e;
        } catch (final IOException e) {
            // Linux and macOS open a directory as if it were a file, which fails only when it is read. It is not a
            // file entry
            if (Files.isDirectory(path != null ? path : resolveCanonicalName(name))) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Resolve an exact root-relative path to a filesystem {@link Path}.
     *
     * @param name
     *            the root-relative path, or {@code ""} for the root itself.
     * @return the resolved {@link Path}, or {@code null} if invalid, outside the root, or not in canonical form.
     */
    @Nullable
    Path pathForNio(final String name) {
        return resolveExactName(name);
    }

    /**
     * Return the path of a file of this root that was looked up without building its path, which is only done for a
     * name that {@link #lastSlashIdxIfCanonical(String)} accepts, when {@link #dirPrefix} is not null.
     *
     * @param name
     *            the root-relative path of the file.
     * @return the path, the same one that {@link #resolveExactName(String)} resolves the name to.
     */
    Path resolveCanonicalName(final String name) {
        return dir.resolve(name);
    }

    /**
     * Resolve an exact root-relative path to a filesystem {@link Path}, without reading the filesystem.
     *
     * @param name
     *            the root-relative path, or {@code ""} for the root itself.
     * @return the resolved {@link Path}, or {@code null} if invalid, outside the root, or not in canonical form.
     */
    private @Nullable Path resolveExactName(final String name) {
        final Path resolved;
        try {
            if (dirPrefix != null && lastSlashIdxIfCanonical(name) != NOT_CANONICAL) {
                // On a filesystem with '/' as its separator, a name with no empty, "." or ".." segments resolves to
                // dirPrefix followed by the name, a path under the root that is already normalized and exact
                return dir.resolve(name);
            }
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
        return isExactName(name, resolved) ? resolved : null;
    }

    /** The value of {@link #lastSlashIdxIfCanonical(String)} for a name that is not in canonical form. */
    private static final int NOT_CANONICAL = -2;

    /**
     * Check that a root-relative path has no empty, {@code "."} or {@code ".."} segments, so that it does not begin
     * or end with {@code '/'} or hold two in a row, and holds no NUL character, which no path on the filesystems
     * with {@code '/'} as their separator can hold.
     *
     * @param name
     *            the root-relative path.
     * @return the index of the last {@code '/'} in {@code name}, or -1 if it has none, if every segment of
     *         {@code name} is a name other than {@code "."} or {@code ".."} and it holds no NUL character,
     *         otherwise {@link #NOT_CANONICAL}.
     */
    private static int lastSlashIdxIfCanonical(final String name) {
        // String#indexOf is compiled to vector instructions, so the name is searched with it rather than one
        // character at a time
        if (name.indexOf('\0') >= 0) {
            return NOT_CANONICAL;
        }
        var segmentStart = 0;
        while (true) {
            final var slashIdx = name.indexOf('/', segmentStart);
            final var segmentLength = (slashIdx < 0 ? name.length() : slashIdx) - segmentStart;
            if (segmentLength == 0 || segmentLength <= 2 && name.charAt(segmentStart) == '.'
                    && (segmentLength == 1 || name.charAt(segmentStart + 1) == '.')) {
                return NOT_CANONICAL;
            }
            if (slashIdx < 0) {
                // The last segment begins after the last '/'
                return segmentStart - 1;
            }
            segmentStart = slashIdx + 1;
        }
    }

    /**
     * Look up a regular file entry by root-relative path, including unreadable files, with one metadata read and
     * without consulting {@link #entriesByName}, so that a NIO view sees files deleted since the root was listed.
     *
     * @param name
     *            the root-relative path.
     * @return the file entry, or {@code null} if not a regular file.
     */
    @Nullable
    DirEntry fileEntryForNio(final String name) {
        final var path = resolveExactName(name);
        return path == null ? null : fileEntry(name, name.lastIndexOf('/'), path);
    }

    /**
     * Look up a regular file entry, with one metadata read.
     *
     * @param name
     *            the root-relative path.
     * @param lastSlashIdx
     *            the index of the last {@code '/'} in {@code name}, or -1 if it has none.
     * @param path
     *            the path that {@link #resolveExactName(String)} resolved {@code name} to, or null if the name was
     *            not resolved, which is only done for a name that {@link #lastSlashIdxIfCanonical(String)} accepts.
     * @return the file entry, or {@code null} if not a regular file.
     */
    private @Nullable DirEntry fileEntry(final String name, final int lastSlashIdx, final @Nullable Path path) {
        final var dirName = lastSlashIdx < 0 ? "" : name.substring(0, lastSlashIdx);
        final DirHandle handle;
        try {
            handle = dirHandle(dirName);
        } catch (final NoSuchFileException | NotDirectoryException _) {
            // The file's directory does not exist, so neither does the file
            return null;
        }
        // If the path was not built, the name of the file within its directory is made from the string, which costs
        // less than building the path and taking the name from it
        final var fileName = handle == null ? null
                : path != null ? path.getFileName() : dir.getFileSystem().getPath(name.substring(lastSlashIdx + 1));
        final var attributes = readAttributes(dirName, handle, fileName, name, path);
        if (attributes == null || !attributes.isRegularFile()) {
            return null;
        }
        // The entry keeps the handle, so that the file is opened through it without looking it up again
        return new DirEntry(this, path, name, attributes, handle, fileName);
    }
}
