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
import java.io.UncheckedIOException;
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
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
 * A root stops working once it is closed, or once the {@link Vfs} that produced it is closed.
 *
 * <p>
 * Iterating a root iterates its entries, in the same order as {@link #getEntries()}.
 *
 * <p>
 * Every method is safe to call from multiple threads at once, and {@link #close()} takes effect the moment it is
 * called, so a thread that lists or reads entries after that -- even while the close is still running -- gets an
 * {@link IOException} rather than entries of storage that is being released.
 */
public abstract class VfsRoot implements AutoCloseable, Iterable<VfsEntry> {
    /** The {@link Vfs} that opened this root. */
    private final Vfs vfs;

    /** True once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean();

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
     * Returns the module name of this root: the name of the module for a module root, and the value of the
     * {@code Automatic-Module-Name} manifest entry for a jarfile.
     *
     * @return the module name, or null if there is none.
     */
    public @Nullable String getModuleName() {
        return null;
    }

    /**
     * Returns the jarfile that this root reads from. This is for the other ClassGraph modules, which need the
     * jarfile's manifest and central directory as well as its entries, and is not part of the API.
     *
     * @return the jarfile, or null if this root is not an archive.
     * @hidden
     */
    public @Nullable LogicalZipFile getLogicalZipFile() {
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

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
     * was given. This is for the other ClassGraph modules, which nest what the virtual filesystem logs under the
     * part of the scan log that it belongs to, and is not part of the API.
     *
     * @param visitor
     *            the visitor to hand the entries to.
     * @param log
     *            the log node, or null to not log.
     * @throws IOException
     *             if the entries could not be listed, or if the {@link Vfs} has been closed.
     * @hidden
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
     * is reported unless {@link Vfs#enableMultiReleaseVersions()} was called.
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
     * Returns the entry with the given name, or null if there is no such entry. If the root contains more than one
     * entry with the same name, the first one is returned, which is the one a classloader would find.
     *
     * @param name
     *            the name of the entry, relative to the package root, e.g. {@code "com/xyz/Widget.class"}.
     * @return the entry, or null if there is no entry with that name.
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
     * @return the entry, or null if there is no entry with that name.
     * @throws IOException
     *             if the root could not be searched.
     */
    abstract @Nullable VfsEntry getEntryImpl(String name) throws IOException;

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link FileSystem} view of this root, created on first use. */
    private volatile @Nullable FileSystem fileSystem;

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
     * {@link java.nio.file.ReadOnlyFileSystemException}. Its {@link FileSystem#close()} closes this root, and
     * closing this root closes the filesystem, so either one can be used in a try-with-resources. Neither releases
     * the file handles, memory mappings and temporary files behind the root, which belong to the {@link Vfs} --
     * close the {@link Vfs} to release those.
     *
     * @return a {@link FileSystem} view of this root. The same instance is returned every time.
     */
    public FileSystem asFileSystem() {
        var fs = fileSystem;
        if (fs == null) {
            synchronized (this) {
                fs = fileSystem;
                if (fs == null) {
                    fileSystem = fs = new VfsFileSystem(this);
                }
            }
        }
        return fs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Close this root, dropping the {@link FileSystem} view of it if one was created, and removing it from the
     * cache of the {@link Vfs} that opened it, so that opening the same path again builds a new root. Every
     * {@link VfsEntry} this root handed out stops working, as does the {@link FileSystem} view, which from then on
     * throws {@link java.nio.file.ClosedFileSystemException}.
     *
     * <p>
     * This releases nothing that a root shares with the rest of the {@link Vfs} -- the jarfile that backs it may
     * back other roots too, and stays open along with the file handles, memory mappings and temporary files behind
     * it. Call {@link Vfs#close()} to release those.
     *
     * <p>
     * Closing an already-closed root has no effect.
     */
    @Override
    public void close() {
        // The flag is set atomically, so that a second call (or a concurrent one) returns rather than dropping the
        // FileSystem view twice, and so that a thread listing or reading entries the moment a close starts is
        // turned away
        if (closed.getAndSet(true)) {
            return;
        }
        // The FileSystem view is the only thing a root creates for itself. It holds no file handles, only an index
        // of the entry names, which is dropped here rather than kept alive by a closed root.
        fileSystem = null;
        vfs.rootClosed(this);
    }

    /**
     * Returns whether this root has been closed, either directly or by closing the {@link Vfs} that opened it.
     *
     * @return true if this root has been closed.
     */
    boolean isClosed() {
        return closed.get() || vfs.isClosed();
    }

    /**
     * Throw an {@link IOException} if this root, or the {@link Vfs} that opened it, has been closed.
     *
     * @param what
     *            what was being read, for the error message.
     * @throws IOException
     *             if this root, or the {@link Vfs} that opened it, has been closed.
     */
    void checkNotClosed(final String what) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read " + what + " after the VfsRoot has been closed");
        }
        vfs.checkNotClosed(what);
    }

    /**
     * Returns the path of this root, with the package root appended if there is one.
     *
     * @return the root, as a string.
     */
    @Override
    public String toString() {
        final var packageRoot = getPackageRoot();
        return packageRoot.isEmpty() ? getPath() : getPath() + "!/" + packageRoot;
    }
}
