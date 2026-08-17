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
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.ManifestParser;
import org.jspecify.annotations.Nullable;

/**
 * A tree of files that was opened by a {@link Vfs}: a directory, a jarfile (which may be nested within other
 * jarfiles), or a module.
 *
 * <p>
 * Whichever of those it is, the entries are named the same way -- relative to the root, with {@code '/'} as the
 * separator -- and are read through the same {@link VfsEntry} methods, so code that walks a root does not have to
 * know which kind it is. {@link #getKind()} says which kind it is, for the cases that do need to know.
 *
 * <p>
 * A root is not {@link AutoCloseable}, and owns nothing that has to be released: the file handles, memory mappings
 * and temporary files it reads through belong to the {@link Vfs}, which hands out the same root to every caller
 * that asks for the same path. A root stops working once that {@link Vfs} is closed, and not before.
 *
 * <p>
 * Iterating a root iterates its entries, in the same order as {@link #getEntries()}.
 *
 * <p>
 * Every method is safe to call from multiple threads at once, and {@link Vfs#close()} takes effect the moment it is
 * called, so a thread that lists or reads entries after that -- even while the close is still running -- gets an
 * {@link IOException} rather than entries of storage that is being released.
 */
public abstract class VfsRoot implements Iterable<VfsEntry> {
    /** The {@link Vfs} that opened this root. */
    private final Vfs vfs;

    /**
     * Constructor.
     *
     * @param vfs
     *            the {@link Vfs} that opened this root.
     */
    VfsRoot(final Vfs vfs) {
        this.vfs = vfs;
    }

    /** What is backing a {@link VfsRoot}. */
    public enum Kind {
        /** A directory in a filesystem. */
        DIRECTORY,

        /** A zipfile or jarfile, which may itself be nested within other jarfiles. */
        ARCHIVE,

        /** A module of the module path, or of the running JDK. */
        MODULE
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
     * Returns what is backing this root.
     *
     * @return the kind of root.
     */
    public abstract Kind getKind();

    /**
     * Returns the path of this root: the directory path, or the path of the jarfile with {@code "!/"} separating
     * each nested jarfile from the one that encloses it, or the module name. The package root is not part of the
     * path.
     *
     * <p>
     * A directory or jarfile is named by its canonical path, which is not always the path the root was opened from:
     * a symlink, or, on Windows, an 8.3 short name, reaches the same directory or jarfile under another name.
     *
     * @return the path of the root.
     */
    public abstract String getPath();

    /**
     * Returns the {@link URI} of this root.
     *
     * @return the {@link URI} of the root.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed, which includes the case of a module that does not know
     *             its own location.
     */
    public abstract URI getURI();

    /**
     * Returns the {@link URL} of this root.
     *
     * @return the {@link URL} of the root.
     * @throws IllegalStateException
     *             if the {@link URL} could not be formed, which includes the case of a {@link URI} whose scheme has
     *             no protocol handler installed.
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
     * Returns the {@link File} backing this root.
     *
     * @return the {@link File} of the directory or jarfile, or null if this root is a module, or is a jarfile that
     *         was read from a stream or downloaded from a URL into RAM rather than to a temporary file.
     */
    public @Nullable File getFile() {
        return null;
    }

    /**
     * Returns the {@link Path} backing this root.
     *
     * @return the {@link Path} of the directory or jarfile, or null if this root is a module, or is a jarfile that
     *         was read from a stream or downloaded from a URL into RAM rather than to a temporary file.
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
     * Returns the package root within this root: the directory that {@link VfsEntry#getName()} is relative to,
     * without a trailing {@code '/'}. This is the empty string unless the path this root was opened from ended in a
     * {@code "!/"} section that named a directory rather than a nested jarfile, as it does for a Spring Boot
     * application's {@code "app.jar!/BOOT-INF/classes"}.
     *
     * @return the package root, or the empty string if the whole root is the package root.
     */
    public String getPackageRoot() {
        return "";
    }

    /**
     * Returns this root with its package root removed, so that the rest of the container it was opened within can
     * be reached. For a Spring Boot application's {@code "app.jar!/BOOT-INF/classes"} that is the whole of
     * {@code app.jar}, including the {@code "BOOT-INF/lib/"} directory of jarfiles the application depends on,
     * which lies outside the package root and is therefore invisible from this root.
     *
     * <p>
     * Closing this root closes the returned root too.
     *
     * @return the container this root was opened within, or this root itself if it was not opened at a package
     *         root, which is always the case for a directory and for a module.
     * @throws IOException
     *             if the {@link Vfs} has been closed.
     */
    public VfsRoot getContainerRoot() throws IOException {
        checkNotClosed(getPath());
        return this;
    }

    /**
     * Returns the module name of this root: the name of the module for a module root, and the value of the
     * {@code Automatic-Module-Name} manifest entry otherwise.
     *
     * @return the module name, or null if there is none.
     * @throws IOException
     *             if the manifest could not be read, or if the {@link Vfs} has been closed.
     */
    public @Nullable String getModuleName() throws IOException {
        return getManifestEntry(AUTOMATIC_MODULE_NAME_KEY);
    }

    /**
     * Walk the entries under the package root, not including directories, offering each directory to the visitor
     * before the entries in it so that unwanted ones can be skipped. This is the cheapest way to enumerate a root,
     * since unlike {@link #getEntries()} it neither builds a list of every entry nor, for a directory tree, lists a
     * directory whose entries the visitor does not want.
     *
     * <p>
     * The entries of each directory are walked in the same order that {@link #getEntries()} reports them in. See
     * {@link VfsVisitor#enterDirectory(String)} for how much a skipped directory skips, which differs between a
     * directory tree and an archive.
     *
     * @param visitor
     *            the visitor to hand the entries to.
     * @throws IOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     */
    public final void walk(final VfsVisitor visitor) throws IOException {
        Assert.notNull(visitor, "visitor");
        walk(visitor, getVfs().log());
    }

    /**
     * Walk the entries under the package root, logging to the given log node rather than to the one the {@link Vfs}
     * was given. This is for callers that write a log of their own, and want what the virtual filesystem logs
     * nested under the part of it that it belongs to.
     *
     * @param visitor
     *            the visitor to hand the entries to.
     * @param log
     *            the log node, or null to not log.
     * @throws IOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     */
    public final void walk(final VfsVisitor visitor, final @Nullable LogNode log) throws IOException {
        Assert.notNull(visitor, "visitor");
        checkNotClosed(getPath());
        walkImpl(visitor, log);
    }

    /**
     * Walk the entries under the package root, once it is known that this root is open.
     *
     * @param visitor
     *            the visitor to hand the entries to.
     * @param log
     *            the log node, or null to not log.
     * @throws IOException
     *             if the entries could not be listed.
     */
    abstract void walkImpl(VfsVisitor visitor, @Nullable LogNode log) throws IOException;

    /**
     * Returns the entries under the package root, not including directories.
     *
     * <p>
     * A jarfile's entries come back in the order they appear in its central directory, and a module's sorted by
     * name. A directory tree's are walked from the top down, each directory's own files before its subdirectories,
     * and the children of a directory sorted by name. For a jarfile, encrypted entries and entries stored with an
     * unsupported compression method are left out, and only the newest version of each entry that this JVM can run
     * is reported, unless the {@link Vfs} was constructed with multi-release versions enabled.
     *
     * <p>
     * For a directory, an entry may name a file that the process has no permission to read: the walk tells the
     * files from the subdirectories with the metadata it already reads, rather than spending a second syscall per
     * file on a permission check. Reading such an entry throws an {@link IOException}, and
     * {@link #getEntry(String)} returns null for its name.
     *
     * @return the entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     */
    public final List<VfsEntry> getEntries() throws IOException {
        checkNotClosed(getPath());
        return getEntriesImpl();
    }

    /**
     * Returns an iterator over the entries under the package root, in the same order as {@link #getEntries()}, so
     * that a root can be iterated directly:
     *
     * <pre>
     * for (VfsEntry entry : root) {
     *     System.out.println(entry.getName());
     * }
     * </pre>
     *
     * <p>
     * Listing the entries can fail, and {@link Iterable#iterator()} cannot declare a checked exception, so the
     * {@link IOException} that {@link #getEntries()} throws is wrapped in an {@link UncheckedIOException} here, the
     * same way {@link java.nio.file.DirectoryStream} wraps it. Call {@link #getEntries()} instead, to handle that
     * failure as a checked exception.
     *
     * @return an iterator over the entries.
     * @throws UncheckedIOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     */
    @Override
    public final Iterator<VfsEntry> iterator() {
        try {
            return getEntries().iterator();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the entries under the package root, once it is known that this root is open.
     *
     * @return the entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed.
     */
    abstract List<VfsEntry> getEntriesImpl() throws IOException;

    /**
     * Walk a list of entries that is already in hand, telling the visitor about the directory an entry is in
     * whenever it differs from the directory of the entry before it.
     *
     * @param entryList
     *            the entries, in the order they should be walked in.
     * @param visitor
     *            the visitor to hand the entries to.
     */
    static void walkEntryList(final List<VfsEntry> entryList, final VfsVisitor visitor) {
        // A one-directory memory is enough, because the entries of a jarfile or a module are almost always grouped
        // by directory, so this asks the visitor about a directory once rather than once per entry in it
        String prevDirName = null;
        var prevDirWanted = false;
        for (final var entry : entryList) {
            final var name = entry.getName();
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
     * Collect every entry a walk reaches into a list.
     *
     * @param entriesOut
     *            the list to add the entries to.
     * @return a visitor that skips nothing.
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
     * Returns the entry with the given name, or null if there is no such entry, or it cannot be read. If the root
     * contains more than one entry with the same name, the first one is returned, which is the one a classloader
     * would find.
     *
     * <p>
     * Null does not distinguish between the reasons for it: for a directory, the name may not exist, may name a
     * directory rather than a file, may name a file that the process has no permission to read, or may point
     * outside the root once {@code ".."} sections are resolved; for a jarfile or a module, the name may not exist,
     * or may be an entry that this root does not report (an encrypted entry, an entry stored with an unsupported
     * compression method, or an entry hidden by a newer multi-release version of itself). Test for the file
     * directly if the difference matters.
     *
     * <p>
     * A directory that is walked with {@link #walk(VfsVisitor)} or listed with {@link #getEntries()} does report an
     * unreadable file as an entry, because the walk tells the files from the subdirectories with the metadata it
     * already reads and does not spend a second syscall per file on a permission check. Reading such an entry then
     * throws an {@link IOException}. So a name that a walk reports can still come back null from this method.
     *
     * <p>
     * The name is matched exactly, including the case of every character, on every operating system and for every
     * kind of root, so that one jarfile answers the same question the same way wherever it is read, and so that a
     * name this method finds is a name a classloader will also find. Windows and macOS have case-insensitive
     * filesystems, and a lookup that such a filesystem answered by folding the case of the name is rejected here,
     * rather than returning an entry under a name that is not the one it is stored under. Use
     * {@link #getEntryCaseInsensitive(String)} to match the name case-insensitively instead. A name that reaches a
     * file through a symbolic link is the one exception: following the link changes the path by more than the case
     * of its characters, which is the only thing that tells a folded name from a followed link, so on a
     * case-insensitive filesystem such a name can still be found in a case it is not stored in.
     *
     * @param name
     *            the name of the entry, relative to the package root, e.g. {@code "com/xyz/Widget.class"}.
     * @return the entry, or null if there is no readable entry with that name.
     * @throws IOException
     *             if the root could not be searched, or if the {@link Vfs} has been closed.
     */
    public final @Nullable VfsEntry getEntry(final String name) throws IOException {
        Assert.notNull(name, "name");
        checkNotClosed(name);
        return getEntryImpl(name);
    }

    /**
     * Returns the entry with the given name, once it is known that this root is open.
     *
     * @param name
     *            the name of the entry, relative to the package root.
     * @return the entry, or null if there is no readable entry with that name.
     * @throws IOException
     *             if the root could not be searched.
     */
    abstract @Nullable VfsEntry getEntryImpl(String name) throws IOException;

    /**
     * Returns whether a filesystem found a file under a path that differs from the path it was looked up by only in
     * the case of its characters, which is how a case-insensitive filesystem answers a lookup for a name whose case
     * does not match the name the file is stored under.
     *
     * @param dir
     *            the directory the lookup was made within.
     * @param path
     *            the path the file was looked up by, which the filesystem found a file at.
     * @return true if the file is stored under a path that differs from the one it was looked up by only in case.
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
        // a difference of case alone is what tells a case-folded match from a link that was followed. The two names
        // are compared as strings, since Path#equals ignores the case of a path on Windows, which is the very
        // difference being looked for. The directory is left out of the comparison, since it is spelled the way
        // this root was opened at it, which is not always the way it is spelled on disk
        final var realName = dir.relativize(realPath).toString();
        final var name = dir.relativize(path).toString();
        return !realName.equals(name) && realName.equalsIgnoreCase(name);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the first entry whose name matches the given name when the case of both is ignored, or null if there
     * is no such entry, or it cannot be read. This is the entry that a case-insensitive filesystem would find the
     * name at, on every operating system and for every kind of root.
     *
     * <p>
     * A root can hold more than one entry whose names differ only in case: a zipfile is free to store both
     * {@code "META-INF/MANIFEST.MF"} and {@code "meta-inf/manifest.mf"}, and a case-sensitive filesystem is free to
     * hold both as files. The first of them in the order that {@link #getEntries()} reports is the one returned
     * here, whether or not one of them matches the name exactly; use {@link #getEntry(String)} first if an exactly
     * named entry should win over an earlier one that only matches when case is ignored, and
     * {@link #getEntriesCaseInsensitive(String)} to see all of them.
     *
     * <p>
     * Null covers the same cases here as it does for {@link #getEntry(String)}.
     *
     * @param name
     *            the name of the entry, relative to the package root, e.g. {@code "com/xyz/Widget.class"}.
     * @return the entry, or null if no readable entry has that name when the case of both is ignored.
     * @throws IOException
     *             if the root could not be searched, or if the {@link Vfs} has been closed.
     */
    public final @Nullable VfsEntry getEntryCaseInsensitive(final String name) throws IOException {
        Assert.notNull(name, "name");
        final var matchingEntries = findEntriesCaseInsensitive(name, /* firstMatchOnly = */ true);
        return matchingEntries.isEmpty() ? null : matchingEntries.get(0);
    }

    /**
     * Returns every entry whose name matches the given name when the case of both is ignored, in the order that
     * {@link #getEntries()} reports them in. A root can hold more than one such entry: a zipfile is free to store
     * both {@code "META-INF/MANIFEST.MF"} and {@code "meta-inf/manifest.mf"}, and a case-sensitive filesystem is
     * free to hold both as files.
     *
     * @param name
     *            the name of the entry, relative to the package root, e.g. {@code "com/xyz/Widget.class"}.
     * @return the matching entries, as an unmodifiable list.
     * @throws IOException
     *             if the root could not be searched, or if the {@link Vfs} has been closed.
     */
    public final List<VfsEntry> getEntriesCaseInsensitive(final String name) throws IOException {
        Assert.notNull(name, "name");
        return Collections.unmodifiableList(findEntriesCaseInsensitive(name, /* firstMatchOnly = */ false));
    }

    /**
     * Find the entries whose name matches the given name when the case of both is ignored.
     *
     * @param name
     *            the name of the entry, relative to the package root.
     * @param firstMatchOnly
     *            true to stop the search at the first match.
     * @return the matching entries.
     * @throws IOException
     *             if the root could not be searched, or if the {@link Vfs} has been closed.
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
                if (entry.getName().equalsIgnoreCase(name)) {
                    matchingEntries.add(entry);
                    return !firstMatchOnly;
                }
                return true;
            }
        });
        return matchingEntries;
    }

    /**
     * Returns the entries under the package root whose name starts with the given prefix, not including
     * directories. A prefix ending in {@code '/'} names a directory, and everything beneath it is returned, however
     * deeply nested.
     *
     * <p>
     * The entries come back in the same order as {@link #getEntries()} reports them in, and the same entries are
     * left out as it leaves out. For a directory tree, only the directories that could hold an entry with this
     * prefix are listed, so this costs much less than listing the whole tree.
     *
     * @param pathPrefix
     *            the prefix to match, relative to the package root, with {@code '/'} as the separator and no
     *            leading {@code '/'}, e.g. {@code "BOOT-INF/lib/"}. The empty string matches every entry.
     * @return the matching entries, as an unmodifiable list.
     * @throws IOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     */
    public final List<VfsEntry> getEntries(final String pathPrefix) throws IOException {
        Assert.notNull(pathPrefix, "pathPrefix");
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
                if (entry.getName().startsWith(pathPrefix)) {
                    matchingEntries.add(entry);
                }
                return true;
            }
        });
        return Collections.unmodifiableList(matchingEntries);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The path of the manifest file within a root. */
    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    /** The {@code "Automatic-Module-Name"} manifest key. */
    private static final String AUTOMATIC_MODULE_NAME_KEY = "Automatic-Module-Name";

    /** The manifest, read on first use, or null if this root has no manifest file. */
    private @Nullable Map<String, String> manifest;

    /** True once the manifest has been read, whether or not there turned out to be one. */
    private boolean manifestRead;

    /**
     * Returns the main section of this root's manifest file, {@code META-INF/MANIFEST.MF}, which is read and parsed
     * the first time it is asked for and cached from then on. A root opened at a package root reports the manifest
     * of the container it was opened within, since that is the one that describes the jarfile as a whole.
     *
     * <p>
     * A manifest is really only meaningful for a jarfile, but it is read the same way for a directory and for a
     * module, so an exploded jarfile is described by its manifest just as the jarfile it was exploded from is.
     *
     * @return the manifest attributes, keyed case-insensitively by attribute name, as an unmodifiable map, or null
     *         if this root has no manifest file.
     * @throws IOException
     *             if the manifest file could not be read, or if the {@link Vfs} has been closed.
     */
    public synchronized @Nullable Map<String, String> getManifest() throws IOException {
        // Checked even when the manifest has already been read, so that a root of a closed Vfs reports itself
        // as closed
        // rather than answering out of a cache that was warmed while it was open
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
     * Returns the value of one attribute of this root's manifest file, reading the manifest if it has not already
     * been read.
     *
     * @param key
     *            the name of the attribute, e.g. {@code "Class-Path"}. Manifest attribute names are case
     *            insensitive.
     * @return the value of the attribute, or null if this root has no manifest file, or its manifest does not
     *         declare that attribute.
     * @throws IOException
     *             if the manifest file could not be read, or if the {@link Vfs} has been closed.
     */
    public final @Nullable String getManifestEntry(final String key) throws IOException {
        Assert.notNull(key, "key");
        final var manifestAttributes = getManifest();
        return manifestAttributes == null ? null : manifestAttributes.get(key);
    }

    /**
     * Read and parse the manifest file of this root, through the same entry lookup and read that any other file of
     * the root is read through.
     *
     * @return the manifest attributes, or null if this root has no manifest file.
     * @throws IOException
     *             if the manifest file could not be read, or if the {@link Vfs} has been closed.
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
     * Returns whether this root is searched for a manifest stored under a differently-cased name, when it holds no
     * manifest under the canonical name.
     *
     * @return true to widen the search to ignore case.
     */
    boolean searchesForACaseFoldedManifest() {
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link FileSystem} view of this root, created on first use. */
    private volatile @Nullable VfsFileSystem fileSystem;

    /**
     * Returns a read-only {@link FileSystem} view of this root, so that it can be read through
     * {@link java.nio.file.Files} and {@link Path} whatever kind of storage it actually is -- a directory, a
     * jarfile, a jarfile nested inside another jarfile, a package root, a jarfile that exists only in RAM, or a
     * module.
     *
     * <p>
     * The path separator is {@code '/'} and the root directory is {@code "/"}, whichever kind of root this is.
     * Directories are synthesized from the names of the entries below them, since a jarfile need not contain an
     * entry for every directory whose contents it holds, and the whole entry list is read to do that, so the first
     * call is as expensive as {@link #getEntries()}.
     *
     * <p>
     * The filesystem is read-only: every operation that would write throws
     * {@link java.nio.file.ReadOnlyFileSystemException}. Its {@link FileSystem#close()} closes only that view of
     * this root, and not the root itself, which other callers may be reading through -- so the returned filesystem
     * can be used in a try-with-resources, and the next caller is handed a new view rather than the closed one. It
     * releases nothing either way: the file handles, memory mappings and temporary files behind the root belong to
     * the {@link Vfs}, so close the {@link Vfs} to release those.
     *
     * @return a {@link FileSystem} view of this root. The same instance is returned every time, until it is closed.
     */
    public FileSystem asFileSystem() {
        var fs = fileSystem;
        if (fs == null || fs.isClosedView()) {
            synchronized (this) {
                fs = fileSystem;
                if (fs == null || fs.isClosedView()) {
                    fileSystem = fs = new VfsFileSystem(this);
                }
            }
        }
        return fs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns whether the {@link Vfs} that opened this root has been closed.
     *
     * @return true if the {@link Vfs} has been closed.
     */
    boolean isClosed() {
        return vfs.isClosed();
    }

    /**
     * Throw an {@link IOException} if the {@link Vfs} that opened this root has been closed.
     *
     * @param what
     *            what was being read, for the error message.
     * @throws IOException
     *             if the {@link Vfs} that opened this root has been closed.
     */
    void checkNotClosed(final String what) throws IOException {
        vfs.checkNotClosed(what);
    }

    /**
     * Returns the path this root reports itself at: the path of the directory or jarfile that backs it, with the
     * package root appended if it was opened at one. This is what fully names a root, so it is the path {@link Vfs}
     * caches it under, as well as what {@link #toString()} returns.
     *
     * @return the path of this root, with the package root appended if there is one.
     */
    String reportedPath() {
        final var packageRoot = getPackageRoot();
        return packageRoot.isEmpty() ? getPath() : getPath() + "!/" + packageRoot;
    }

    /**
     * Returns the path of this root, with the package root appended if there is one.
     *
     * @return the root, as a string.
     */
    @Override
    public String toString() {
        return reportedPath();
    }
}
