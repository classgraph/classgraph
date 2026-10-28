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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.ManifestParser;

/**
 * A file tree opened by a {@link Vfs}: a directory ({@link DirRoot}), an archive ({@link ArchiveRoot}), or a module
 * ({@link ModuleRoot}).
 *
 * <p>
 * Entries are named relative to the root using {@code '/'} as the separator and accessed uniformly through
 * {@link VfsEntry}. Iterating a root yields its entries in the same order as {@link #getEntries()}.
 *
 * <p>
 * Closing a {@link Vfs} closes all roots it opened. Calling {@link #close()} on an individual root releases that
 * root's resources (and any child roots opened within it, such as nested archives or package root views) and
 * removes it from the {@link Vfs} cache while leaving the {@link Vfs} and other roots open. All methods are
 * thread-safe, and operations on a closed root throw {@link IOException}.
 */
public abstract sealed class VfsRoot implements Iterable<VfsEntry>, AutoCloseable
        permits ArchiveRoot, DirRoot, ModuleRoot {
    /** The {@link Vfs} that opened this root. */
    private final Vfs vfs;

    /** Child roots opened within this root (nested archives or package root views). */
    private final Set<VfsRoot> children = ConcurrentHashMap.newKeySet();

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     */
    VfsRoot(final Vfs vfs) {
        this.vfs = vfs;
    }

    /**
     * Register a child root opened within this root so it is closed when this root closes.
     *
     * @param child
     *            the child root.
     */
    void addChild(final VfsRoot child) {
        children.add(child);
        child.addUnregistration(() -> children.remove(child));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the {@link Vfs} that opened this root.
     *
     * @return the {@link Vfs}.
     */
    public Vfs getVfs() {
        return vfs;
    }

    /**
     * Returns the file store type reported by {@link java.nio.file.FileStore#type()} for {@link VfsFileSystem}.
     *
     * @return {@code "directory"}, {@code "archive"}, or {@code "module"}.
     */
    abstract String fileStoreType();

    /**
     * Returns the path of this root: the canonical directory path, the canonical jarfile path (with {@code "!/"}
     * separating nested jarfiles), or the module name, excluding any package root suffix.
     *
     * <p>
     * For a directory in a non-default filesystem, returns the URI with its scheme removed (for example,
     * {@code "/a/b.zip!/dir"} for {@code "jar:file:///a/b.zip!/dir"}).
     *
     * @return the path of the root.
     */
    public abstract String getPath();

    /**
     * Returns the last segment of {@link #getPath()}: the innermost jarfile name for an archive (with any temporary
     * extraction prefix stripped), the directory name for a directory, or the module name for a module.
     *
     * @return the last segment of the root's path.
     */
    public String getLastSegment() {
        return PathSyntax.lastSegment(getPath());
    }

    /**
     * Returns the {@link URI} of this root.
     *
     * @return the {@link URI} of the root.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed (for example, for a module without a known location).
     */
    public abstract URI getURI();

    /**
     * Returns the {@link URI} of a relative path within this root ({@code file:}, {@code jar:}, or module URI).
     *
     * <p>
     * For a directory or archive, the URI is constructed from the path without checking whether an entry exists;
     * for a module, the module must contain the resource.
     *
     * @param pathWithinRoot
     *            the unencoded path relative to this root, with {@code '/'} as the separator.
     * @return the {@link URI} of the path.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed (including when a module does not contain the path).
     */
    public abstract URI resolveURI(String pathWithinRoot);

    /**
     * Returns the {@link URI} of a path within this root without checking whether the entry exists.
     *
     * @param pathWithinRoot
     *            the path relative to this root with {@code '/'} as the separator, or {@code ""} for the root.
     * @return the {@link URI} of the path.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed.
     */
    URI uriOfPath(final String pathWithinRoot) {
        return resolveURI(pathWithinRoot);
    }

    /**
     * Returns the {@code "jar:"} URI prefix for a jarfile URI string, escaping any {@code "!/"} in a non-jar URI as
     * {@code "%21/"}.
     *
     * @param jarfileURIStr
     *            the URI of the jarfile.
     * @return the {@code "jar:"} URI of the jarfile.
     */
    static String jarURIOf(final String jarfileURIStr) {
        // A jarfile nested within another jarfile already has a "jar:" URI, and must not be given a second one.
        // Otherwise the "jar:" scheme ends the jarfile's URI at its first "!/". So a '!' followed by '/' in it, as
        // at the end of a directory named "dir!", is percent-encoded: %21 is the only escape the scheme allows, and
        // the "file:" URL handler decodes it again. Any other '!' is left as it is, since it cannot end the
        // jarfile's URI, and the JDK's own jar: URLs do not encode it
        return jarfileURIStr.startsWith("jar:") ? jarfileURIStr : "jar:" + jarfileURIStr.replace("!/", "%21/");
    }

    /**
     * Returns the {@link URL} of this root.
     *
     * @return the {@link URL} of the root.
     * @throws IllegalStateException
     *             if the {@link URL} could not be formed (for example, if the URI scheme has no installed protocol
     *             handler).
     */
    public URL getURL() {
        final var uri = getURI();
        try {
            return uri.toURL();
        } catch (final IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Could not create URL from URI: " + uri + " : " + e, e);
        }
    }

    /**
     * Returns the local {@link File} backing this root (the outermost jarfile for a nested jarfile, or the
     * temporary file if spilled to disk).
     *
     * @return the backing {@link File}, or null if this root is not backed by a file in the default filesystem
     *         (e.g. a system module, an in-memory jarfile, or a path in a custom filesystem).
     */
    public @Nullable File getFile() {
        return null;
    }

    /**
     * Returns the {@link Path} backing this root (the outermost jarfile for a nested jarfile, or the temporary file
     * if spilled to disk).
     *
     * @return the backing {@link Path}, or null if this root has no backing {@link Path} (e.g. a system module or
     *         an in-memory jarfile).
     */
    public @Nullable Path getNioPath() {
        return null;
    }

    /**
     * Returns the {@link ModuleReference} backing this root.
     *
     * @return the {@link ModuleReference}, or null if this root is not a module.
     */
    public @Nullable ModuleReference getModuleReference() {
        return null;
    }

    /**
     * Returns the package root directory within this root (without a trailing {@code '/'}), or {@code ""} if none
     * (for example, {@code "BOOT-INF/classes"} when opened from {@code "app.jar!/BOOT-INF/classes"}).
     *
     * @return the package root, or {@code ""} if this root is at the top of its container.
     */
    public String getPackageRoot() {
        return "";
    }

    /**
     * Returns the enclosing root without any package root prefix, or this root itself if {@link #getPackageRoot()}
     * is empty.
     *
     * @return the container root.
     * @throws IOException
     *             if this root or its {@link Vfs} has been closed.
     */
    public VfsRoot getContainerRoot() throws IOException {
        checkNotClosed(getPath());
        return this;
    }

    /**
     * Returns the module name of this root: the module's name for a {@link ModuleRoot}, or the
     * {@code Automatic-Module-Name} manifest attribute otherwise.
     *
     * @return the module name, or null if none.
     * @throws IOException
     *             if the manifest could not be read, or if this root or its {@link Vfs} has been closed.
     */
    public @Nullable String getModuleName() throws IOException {
        return getManifestEntry(AUTOMATIC_MODULE_NAME_KEY);
    }

    /**
     * Walk the non-directory entries under the package root, offering each directory to
     * {@link VfsVisitor#enterDirectory(String)} before visiting its entries.
     *
     * @param visitor
     *            the visitor.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    public final void walk(final VfsVisitor visitor) throws IOException {
        Assert.notNull(visitor, "visitor");
        walk(visitor, getVfs().log());
    }

    /**
     * Walk the non-directory entries under the package root, logging to the specified {@link LogNode}.
     *
     * @param visitor
     *            the visitor.
     * @param log
     *            the log node, or null to disable logging.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    public final void walk(final VfsVisitor visitor, final @Nullable LogNode log) throws IOException {
        Assert.notNull(visitor, "visitor");
        checkNotClosed(getPath());
        walkImpl(visitor, log);
    }

    /**
     * Walk the entries under the package root (called after verifying that this root is open).
     *
     * @param visitor
     *            the visitor.
     * @param log
     *            the log node, or null.
     * @throws IOException
     *             if the entries could not be listed.
     */
    abstract void walkImpl(VfsVisitor visitor, @Nullable LogNode log) throws IOException;

    /**
     * Returns all non-directory entries under the package root.
     *
     * <p>
     * Archive entries are returned in central directory order (omitting encrypted entries, entries with unsupported
     * compression methods, duplicate names except the last, and superseded multi-release entries unless disabled
     * via {@link VfsSpec#disableMultiReleaseVersions()}). Module entries are returned sorted by name (omitting
     * non-canonical names). Directory entries are returned top-down with files before subdirectories, sorted by
     * name within each directory (including unreadable files, which fail with {@link IOException} when read and
     * return {@code null} from {@link #getEntry(String)}).
     *
     * @return the entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    public final @Unmodifiable List<VfsEntry> getEntries() throws IOException {
        checkNotClosed(getPath());
        return getEntriesImpl();
    }

    /**
     * Returns an iterator over the entries under the package root in the same order as {@link #getEntries()},
     * wrapping any {@link IOException} in {@link UncheckedIOException}.
     *
     * @return an iterator over the entries.
     * @throws UncheckedIOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    @Override
    public final @Unmodifiable Iterator<VfsEntry> iterator() {
        try {
            return getEntries().iterator();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the entries under the package root (called after verifying that this root is open).
     *
     * @return the entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed.
     */
    abstract List<VfsEntry> getEntriesImpl() throws IOException;

    /**
     * Walk a pre-collected list of entries, invoking {@link VfsVisitor#enterDirectory(String)} whenever the parent
     * directory changes.
     *
     * @param entryList
     *            the entries to walk.
     * @param visitor
     *            the visitor.
     */
    static void walkEntryList(final List<VfsEntry> entryList, final VfsVisitor visitor) {
        // A one-directory memory is enough, because the entries of a jarfile or a module are almost always grouped
        // by directory, so this asks the visitor about a directory once rather than once per entry in it
        String prevDirName = null;
        var prevDirWanted = false;
        for (final var entry : entryList) {
            final var name = entry.getPathFromRoot();
            final var lastSlashIdx = name.lastIndexOf('/');
            final var dirName = lastSlashIdx < 0 ? "/" : name.substring(0, lastSlashIdx + 1);
            if (!dirName.equals(prevDirName)) {
                prevDirName = dirName;
                prevDirWanted = visitor.enterDirectory(dirName);
            }
            if (prevDirWanted && !visitor.visitEntry(entry)) {
                return;
            }
        }
    }

    /**
     * Returns a visitor that collects all visited entries into the given list.
     *
     * @param entriesOut
     *            the list to populate.
     * @return the collecting visitor.
     */
    static VfsVisitor collectingVisitor(final List<VfsEntry> entriesOut) {
        return new VfsVisitor() {
            @Override
            public boolean enterDirectory(final String dirName) {
                return true;
            }

            @Override
            public boolean visitEntry(final VfsEntry entry) {
                entriesOut.add(entry);
                return true;
            }
        };
    }

    /**
     * Returns the readable entry with the given exact, case-sensitive canonical path relative to the package root,
     * or {@code null} if no such readable file entry exists.
     *
     * <p>
     * Names must use {@code '/'} as the separator without leading or trailing slashes, repeated slashes, or
     * {@code "."} / {@code ".."} segments. Case-folded matches on case-insensitive filesystems (such as on Windows
     * and macOS) are rejected; use {@link #getEntryCaseInsensitive(String)} for case-insensitive lookup.
     *
     * @param name
     *            the path of the entry relative to the package root (e.g. {@code "com/xyz/Widget.class"}).
     * @return the entry, or null if no readable entry with that exact name exists.
     * @throws IOException
     *             if the root could not be searched, or if this root or its {@link Vfs} has been closed.
     */
    public final @Nullable VfsEntry getEntry(final String name) throws IOException {
        Assert.notNull(name, "name");
        checkNotClosed(name);
        return getEntryImpl(name);
    }

    /**
     * Returns the entry with the given name (called after verifying that this root is open).
     *
     * @param name
     *            the path of the entry relative to the package root.
     * @return the entry, or null if no readable entry with that name exists.
     * @throws IOException
     *             if the root could not be searched.
     */
    abstract @Nullable VfsEntry getEntryImpl(String name) throws IOException;

    /**
     * Returns whether {@code path} resolved within canonical directory {@code dir} matched a file whose stored name
     * differs only in case (excluding symbolic links whose link name matches the requested case).
     *
     * @param dir
     *            the canonicalized base directory.
     * @param path
     *            the looked-up path resolved against {@code dir}.
     * @return true if the lookup succeeded only via case folding.
     * @throws IOException
     *             if the real path of the file could not be read.
     */
    static boolean isCaseFoldedMatch(final Path dir, final Path path) throws IOException {
        final var realPath = path.toRealPath();
        if (!realPath.startsWith(dir)) {
            // A symbolic link led out of the directory, so the real path is nothing like the path it was reached
            // through, rather than that same path with the case of its characters normalized
            return false;
        }
        // The real path also has symbolic links resolved and, on Windows, 8.3 short names expanded, and either of
        // those can make it differ from the path it was reached through by more than the case of its characters, so
        // a difference of case alone is what marks a possible case-folded match. The two names are compared as
        // strings, since Path#equals ignores the case of a path on Windows, which is the very difference being
        // looked for. The directory is left out of the comparison, since it is spelled the way this root was opened
        // at it, which is not always the way it is spelled on disk
        final var realRelativePath = dir.relativize(realPath);
        final var relativePath = dir.relativize(path);
        final var realName = realRelativePath.toString();
        final var name = relativePath.toString();
        if (realName.equals(name) || !realName.equalsIgnoreCase(name)) {
            return false;
        }
        // A symbolic link whose name differs from the name of its target only in case, such as alias.txt ->
        // Alias.txt, gives the same difference without any folding. What tells the two apart is whether each
        // differently-cased segment is listed in its directory exactly as it was looked up: a link is listed
        // under its own name, whereas a folded name is not listed at all. The listing is compared with
        // String#equals rather than filtered by a glob, since the glob of the Windows filesystem ignores case. The
        // two names differ only in case, so they have the same segments
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            final var segment = relativePath.getName(i).toString();
            if (!segment.equals(realRelativePath.getName(i).toString())
                    && !isListedExactly(i == 0 ? dir : dir.resolve(relativePath.subpath(0, i)), segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether {@code parentDir} contains an entry with the exact case-sensitive name {@code fileName}.
     *
     * @param parentDir
     *            the directory.
     * @param fileName
     *            the entry name.
     * @return true if the directory lists the name with exact case.
     */
    private static boolean isListedExactly(final Path parentDir, final String fileName) {
        try (var listing = Files.newDirectoryStream(parentDir)) {
            for (final var file : listing) {
                if (fileName.equals(file.getFileName().toString())) {
                    return true;
                }
            }
            return false;
        } catch (final IOException | DirectoryIteratorException _) {
            // A directory that can be searched but not read. Without a listing, a name that differs from its real
            // name only in case is taken to be a folded one
            return false;
        }
    }

    /**
     * Returns whether {@code name} is a canonical relative entry path (non-empty, no leading or trailing
     * {@code '/'}, no empty segments, and no {@code "."} or {@code ".."} segments).
     *
     * @param name
     *            the entry name.
     * @return true if the name is in canonical entry form.
     */
    static boolean isCanonicalEntryName(final String name) {
        var sectionStart = 0;
        while (true) {
            final var slashIdx = name.indexOf('/', sectionStart);
            final var sectionEnd = slashIdx < 0 ? name.length() : slashIdx;
            final var sectionLen = sectionEnd - sectionStart;
            if (sectionLen == 0 || (name.charAt(sectionStart) == '.'
                    && (sectionLen == 1 || (sectionLen == 2 && name.charAt(sectionStart + 1) == '.')))) {
                return false;
            }
            if (slashIdx < 0) {
                return true;
            }
            sectionStart = slashIdx + 1;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the first readable entry whose path matches {@code name} case-insensitively (in {@link #getEntries()}
     * order), or {@code null} if no such entry exists.
     *
     * @param name
     *            the path of the entry relative to the package root (e.g. {@code "com/xyz/Widget.class"}).
     * @return the first matching readable entry, or null if none.
     * @throws IOException
     *             if the root could not be searched, or if this root or its {@link Vfs} has been closed.
     */
    public final @Nullable VfsEntry getEntryCaseInsensitive(final String name) throws IOException {
        Assert.notNull(name, "name");
        final var matchingEntries = findEntriesCaseInsensitive(name, /* firstMatchOnly = */ true);
        return matchingEntries.isEmpty() ? null : matchingEntries.get(0);
    }

    /**
     * Returns all readable entries whose path matches {@code name} case-insensitively, in {@link #getEntries()}
     * order.
     *
     * @param name
     *            the path of the entry relative to the package root (e.g. {@code "com/xyz/Widget.class"}).
     * @return the matching entries, as an unmodifiable list.
     * @throws IOException
     *             if the root could not be searched, or if this root or its {@link Vfs} has been closed.
     */
    public final @Unmodifiable List<VfsEntry> getEntriesCaseInsensitive(final String name) throws IOException {
        Assert.notNull(name, "name");
        return Collections.unmodifiableList(findEntriesCaseInsensitive(name, /* firstMatchOnly = */ false));
    }

    /**
     * Find readable entries whose path matches {@code name} case-insensitively.
     *
     * @param name
     *            the path of the entry relative to the package root.
     * @param firstMatchOnly
     *            true to stop at the first match.
     * @return the matching entries.
     * @throws IOException
     *             if the root could not be searched, or if this root or its {@link Vfs} has been closed.
     */
    private List<VfsEntry> findEntriesCaseInsensitive(final String name, final boolean firstMatchOnly)
            throws IOException {
        // Only the directories on the way to the directory that holds the name can hold a matching entry, so this
        // costs no more than a lookup of an exactly-named entry does, beyond listing those directories
        final var dirPrefix = name.substring(0, name.lastIndexOf('/') + 1);
        final List<VfsEntry> matchingEntries = new ArrayList<>();
        walk(new VfsVisitor() {
            @Override
            public boolean enterDirectory(final String dirName) {
                // The root directory is reported as "/", which is the empty prefix
                final var dir = dirName.equals("/") ? "" : dirName;
                return dirPrefix.regionMatches(/* ignoreCase = */ true, 0, dir, 0, dir.length());
            }

            @Override
            public boolean visitEntry(final VfsEntry entry) {
                // A listing reports a file it has not checked can be read, and a lookup must not return one
                if (entry.getPathFromRoot().equalsIgnoreCase(name) && entry.isReadable()) {
                    matchingEntries.add(entry);
                    return !firstMatchOnly;
                }
                return true;
            }
        });
        return matchingEntries;
    }

    /**
     * Returns all non-directory entries under the package root whose path starts with {@code pathPrefix}, in
     * {@link #getEntries()} order.
     *
     * @param pathPrefix
     *            the prefix to match relative to the package root, with {@code '/'} as the separator and no leading
     *            {@code '/'} (e.g. {@code "BOOT-INF/lib/"}, or {@code ""} to match all entries).
     * @return the matching entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    public final @Unmodifiable List<VfsEntry> getEntries(final String pathPrefix) throws IOException {
        Assert.notNull(pathPrefix, "pathPrefix");
        return Collections.unmodifiableList(findEntriesWithPrefix(pathPrefix, /* firstMatchOnly = */ false));
    }

    /**
     * Returns whether any non-directory entry under the package root has a path starting with {@code pathPrefix}.
     *
     * @param pathPrefix
     *            the prefix to match relative to the package root, with {@code '/'} as the separator and no leading
     *            {@code '/'} (e.g. {@code "BOOT-INF/classes/"}, or {@code ""} to test whether any entry exists).
     * @return true if at least one matching entry exists.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    public final boolean hasEntries(final String pathPrefix) throws IOException {
        Assert.notNull(pathPrefix, "pathPrefix");
        // One matching entry is the whole answer
        return !findEntriesWithPrefix(pathPrefix, /* firstMatchOnly = */ true).isEmpty();
    }

    /**
     * Find non-directory entries under the package root whose path starts with {@code pathPrefix}.
     *
     * @param pathPrefix
     *            the prefix to match relative to the package root.
     * @param firstMatchOnly
     *            true to stop at the first match.
     * @return the matching entries.
     * @throws IOException
     *             if the entries could not be listed, or if this root or its {@link Vfs} has been closed.
     */
    private List<VfsEntry> findEntriesWithPrefix(final String pathPrefix, final boolean firstMatchOnly)
            throws IOException {
        // Only the directories on the way to the prefix, and the directories below it, can hold a matching entry
        final var dirPrefix = pathPrefix.substring(0, pathPrefix.lastIndexOf('/') + 1);
        final List<VfsEntry> matchingEntries = new ArrayList<>();
        walk(new VfsVisitor() {
            @Override
            public boolean enterDirectory(final String dirName) {
                // The root directory is reported as "/", which is the empty prefix
                final var dir = dirName.equals("/") ? "" : dirName;
                return dir.startsWith(dirPrefix) || dirPrefix.startsWith(dir);
            }

            @Override
            public boolean visitEntry(final VfsEntry entry) {
                if (entry.getPathFromRoot().startsWith(pathPrefix)) {
                    matchingEntries.add(entry);
                    return !firstMatchOnly;
                }
                return true;
            }
        });
        return matchingEntries;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The path of the manifest file within a root. */
    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    /** The {@code "Automatic-Module-Name"} manifest key. */
    private static final String AUTOMATIC_MODULE_NAME_KEY = "Automatic-Module-Name";

    /** The manifest, read on first use, or null if this root has no manifest file. */
    private @Nullable Map<String, String> manifest;

    /** True once the manifest has been read. */
    private boolean manifestRead;

    /**
     * Returns the main attributes of this root's {@code META-INF/MANIFEST.MF} file (cached on first read), or
     * {@code null} if this root has no manifest. A root opened at a package root returns the manifest of its
     * enclosing container.
     *
     * @return an unmodifiable case-insensitive map of manifest attributes, or null if no manifest exists.
     * @throws IOException
     *             if the manifest file could not be read, or if this root or its {@link Vfs} has been closed.
     */
    public synchronized @Nullable @Unmodifiable Map<String, String> getManifest() throws IOException {
        // Checked even when the manifest has already been read, so that a root of a closed Vfs reports itself as
        // closed rather than answering out of a cache that was warmed while it was open
        checkNotClosed(getPath());
        // This is synchronized rather than double-checked, so that a second thread asking for the manifest while
        // the first is still reading it waits for that read rather than starting a second one
        if (!manifestRead) {
            manifest = readManifest();
            manifestRead = true;
        }
        return manifest;
    }

    /**
     * Returns the value of a single attribute from this root's manifest file.
     *
     * @param key
     *            the case-insensitive attribute name (e.g. {@code "Class-Path"}).
     * @return the attribute value, or null if this root has no manifest or does not declare the attribute.
     * @throws IOException
     *             if the manifest file could not be read, or if this root or its {@link Vfs} has been closed.
     */
    public final @Nullable String getManifestEntry(final String key) throws IOException {
        Assert.notNull(key, "key");
        final var manifestAttributes = getManifest();
        return manifestAttributes == null ? null : manifestAttributes.get(key);
    }

    /**
     * Read and parse this root's manifest file.
     *
     * @return the manifest attributes, or null if this root has no manifest file.
     * @throws IOException
     *             if the manifest file could not be read, or if this root or its {@link Vfs} has been closed.
     */
    @Nullable
    Map<String, String> readManifest() throws IOException {
        // The manifest is looked for under its canonical name first, since that is the name it is stored under in
        // all but a handful of jarfiles, and finding it there costs a single lookup. A jarfile written by a tool
        // that lower-cased its entry names, or exploded onto a filesystem that did, still has a manifest, and
        // java.util.jar.JarFile finds that one too, so the search widens to ignore case rather than reporting that
        // the jarfile has no manifest at all
        var manifestEntry = getEntry(MANIFEST_PATH);
        if (manifestEntry == null && searchesForACaseFoldedManifest()) {
            manifestEntry = getEntryCaseInsensitive(MANIFEST_PATH);
        }
        if (manifestEntry == null) {
            return null;
        }
        try (final InputStream manifestInputStream = manifestEntry.open()) {
            return ManifestParser.parse(manifestInputStream);
        }
    }

    /**
     * Returns whether to fall back to a case-insensitive lookup for {@code META-INF/MANIFEST.MF}.
     *
     * @return true if case-insensitive manifest lookup is enabled.
     */
    boolean searchesForACaseFoldedManifest() {
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link FileSystem} view of this root, created on first use. */
    private volatile @Nullable VfsFileSystem fileSystem;

    /**
     * Returns a read-only {@link FileSystem} view of this root (with {@code '/'} as the separator and {@code "/"}
     * as the root directory).
     *
     * <p>
     * Native directory views include empty directories and reflect live filesystem changes; archive and module
     * views synthesize directories from entry paths. Module entry sizes are computed on demand when
     * {@link java.nio.file.attribute.BasicFileAttributes#size()} is called.
     *
     * <p>
     * Closing the returned {@link FileSystem} closes only that view and its open channels/streams, leaving this
     * root and its {@link Vfs} open; a subsequent call to {@code asFileSystem()} creates a new view. Closing this
     * root or its {@link Vfs} closes the view and releases all underlying resources. (By contrast, a filesystem
     * created directly via {@link java.nio.file.FileSystems#newFileSystem(URI, Map)} or
     * {@link java.nio.file.FileSystems#newFileSystem(Path, Map)} owns its internal {@link Vfs} and closes it when
     * closed.)
     *
     * @return a read-only {@link FileSystem} view of this root (cached until closed).
     * @throws ClosedFileSystemException
     *             if this root or its {@link Vfs} has been closed.
     */
    public FileSystem asFileSystem() {
        // A closed root or Vfs has released the file handles, memory mappings and temporary files that a view
        // reads through, so there is nothing left to hand out a view of. (A root closed just after this check
        // hands back a view whose every read throws ClosedFileSystemException, which is what reading a closed
        // root does.)
        if (isClosed()) {
            throw new ClosedFileSystemException();
        }
        var fs = fileSystem;
        if (fs == null) {
            synchronized (this) {
                fs = fileSystem;
                if (fs == null) {
                    fileSystem = fs = new VfsFileSystem(this, /* ownership = */ null);
                }
            }
        }
        return fs;
    }

    /**
     * Create and cache a {@link VfsFileSystem} that owns this root's {@link Vfs}.
     *
     * @param registeredPath
     *            the path under which the provider registered the filesystem, or null if unregistered.
     * @return the filesystem.
     */
    VfsFileSystem asOwningFileSystem(final @Nullable String registeredPath) {
        final var fs = new VfsFileSystem(this, new VfsFileSystem.Ownership(getVfs(), registeredPath));
        synchronized (this) {
            // The Vfs was created only to open this root, and nothing has been handed this root yet, so there is
            // no earlier view to replace
            fileSystem = fs;
        }
        return fs;
    }

    /**
     * Clear the cached {@link FileSystem} view if it matches {@code view}.
     *
     * @param view
     *            the view being closed.
     */
    void discardFileSystemView(final VfsFileSystem view) {
        synchronized (this) {
            if (fileSystem == view) {
                fileSystem = null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** True once this root has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Callbacks that remove this root from caches when closed. */
    private final List<Runnable> unregistrations = new ArrayList<>();

    /** True once {@link #close(LogNode)} has drained {@link #unregistrations}. */
    private boolean unregistrationsDrained;

    /**
     * Register a callback to remove this root from a cache when closed, running it immediately if already closed.
     *
     * @param unregister
     *            the unregistration callback.
     */
    void addUnregistration(final Runnable unregister) {
        synchronized (unregistrations) {
            if (!unregistrationsDrained) {
                unregistrations.add(unregister);
                return;
            }
        }
        unregister.run();
    }

    /** Open buffers, streams, and channels tracked for closure when this root closes. */
    private final Set<AutoCloseable> openHandles = Collections.newSetFromMap(new IdentityHashMap<>());

    /** True once {@link #close(LogNode)} has closed all handles in {@link #openHandles}. */
    private boolean openHandlesClosed;

    /**
     * Track an open buffer, stream, or channel so it is closed when this root closes.
     *
     * @param handle
     *            the buffer, stream, or channel.
     * @return true if tracked, or false if this root has already closed its open handles.
     */
    boolean trackOpenHandle(final AutoCloseable handle) {
        synchronized (openHandles) {
            if (openHandlesClosed) {
                return false;
            }
            openHandles.add(handle);
            return true;
        }
    }

    /**
     * Remove a closed buffer, stream, or channel from the tracked set.
     *
     * @param handle
     *            the buffer, stream, or channel.
     */
    void untrackOpenHandle(final AutoCloseable handle) {
        synchronized (openHandles) {
            openHandles.remove(handle);
        }
    }

    /**
     * Close all tracked buffers, streams, and channels that remain open.
     *
     * @param log
     *            the log node, or null.
     */
    private void closeOpenHandles(final @Nullable LogNode log) {
        final List<AutoCloseable> handlesToClose;
        synchronized (openHandles) {
            openHandlesClosed = true;
            handlesToClose = new ArrayList<>(openHandles);
            openHandles.clear();
        }
        if (!handlesToClose.isEmpty() && log != null) {
            log.log("Closing " + handlesToClose.size() + " buffer(s), stream(s) or channel(s) that were opened on "
                    + reportedPath() + " and not closed by the caller");
        }
        for (final var handle : handlesToClose) {
            // Each of these takes its close action out atomically, so a caller closing the same one at the same
            // time cannot make it release what it holds twice
            try {
                handle.close();
            } catch (final Exception e) {
                // Closing a root reports nothing to the caller but the log, and there is more to close
                if (log != null) {
                    log.log("Could not close " + handle, e);
                }
            }
        }
    }

    /**
     * Close this root, releasing its resources and removing it from the {@link Vfs} cache.
     *
     * <p>
     * Closes any child roots opened within this root (such as nested archives or package root views) and any
     * unclosed {@link CloseableByteBuffer}s, {@link InputStream}s, or channels opened from this root, while leaving
     * the parent {@link Vfs} and other roots open. Subsequent operations on this root, its entries, or its
     * {@link FileSystem} view throw {@link IOException} or {@link ClosedFileSystemException}. Idempotent; any
     * cleanup failures are logged rather than thrown.
     */
    @Override
    public final void close() {
        close(vfs.log());
    }

    /**
     * Close this root, logging to the specified {@link LogNode}.
     *
     * @param log
     *            the log node, or null.
     */
    final void close(final @Nullable LogNode log) {
        // Remove this root from every cache that holds it before anything else, so that the window in which
        // another thread can be handed a root that is closing is as short as it can be made
        final List<Runnable> unregistrationsToRun;
        synchronized (unregistrations) {
            unregistrationsDrained = true;
            unregistrationsToRun = new ArrayList<>(unregistrations);
            unregistrations.clear();
        }
        for (final var unregister : unregistrationsToRun) {
            unregister.run();
        }
        // Marked closed before anything is released, so that a second close -- or the Vfs's, running concurrently
        // -- returns rather than releasing the same resources twice, and so that a thread about to read through
        // this root is turned away first
        if (closed.getAndSet(true)) {
            return;
        }
        // Close the roots built within this one, depth-first, so that resources are released from the innermost
        // root outwards -- a nested jarfile releases what it holds of the jarfile that encloses it before the
        // enclosing jarfile's own resources go. Each child takes itself out of the children set as it closes, so
        // the walk is over a snapshot; a child added after the snapshot is closed by its creator, which checks
        // whether this root is closed only after adding the child
        for (final var child : new ArrayList<>(children)) {
            child.close(log);
        }
        // Drop the cached FileSystem view, so that this root cannot hand one out while it is closing. The view is
        // not closed: it reports itself closed once this root is (FileSystem#isOpen consults this root), and
        // neither close calls the other
        synchronized (this) {
            fileSystem = null;
        }
        // Close any buffer this root handed out that the caller did not close, so that a buffer that is a view of
        // a memory mapping stops holding it open, and the unmap that releaseResources performs below is the last
        // thing this close does
        closeOpenHandles(log);
        releaseResources(log);
    }

    /**
     * Release resources owned directly by this root.
     *
     * @param log
     *            the log node, or null.
     */
    void releaseResources(final @Nullable LogNode log) {
        // The base root owns nothing
    }

    /**
     * Returns whether this root or its {@link Vfs} has been closed.
     *
     * @return true if this root or its {@link Vfs} has been closed.
     */
    public boolean isClosed() {
        return closed.get() || vfs.isClosed();
    }

    /**
     * Throw {@link IOException} if this root or its {@link Vfs} has been closed.
     *
     * @param what
     *            description of the resource being read.
     * @throws IOException
     *             if this root or its {@link Vfs} has been closed.
     */
    void checkNotClosed(final String what) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read " + what + " after the root has been closed");
        }
        vfs.checkNotClosed(what);
    }

    /**
     * Returns {@link #getPath()} with {@code "!/" + getPackageRoot()} appended if {@link #getPackageRoot()} is
     * non-empty.
     *
     * @return the full path of this root including any package root suffix.
     */
    String reportedPath() {
        final var packageRoot = getPackageRoot();
        return packageRoot.isEmpty() ? getPath() : getPath() + "!/" + packageRoot;
    }

    /**
     * Returns the path of this root, with the package root appended if present.
     *
     * @return the string representation of this root.
     */
    @Override
    public String toString() {
        return reportedPath();
    }
}
