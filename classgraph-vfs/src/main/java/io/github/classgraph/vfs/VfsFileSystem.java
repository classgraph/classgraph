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

import java.io.Closeable;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;

/**
 * A read-only {@link FileSystem} view of a {@link VfsRoot}.
 *
 * <p>
 * The path separator is {@code '/'} and the single root directory is {@code "/"}. Directory-backed views query the
 * underlying filesystem on each operation; archive and module views synthesize directory entries from their
 * resource paths.
 */
final class VfsFileSystem extends FileSystem {
    /** Counter for assigning unique serial numbers to filesystem views. */
    private static final AtomicLong NEXT_SERIAL = new AtomicLong();

    /**
     * Unique identifier for this view, used by {@link VfsPath#compareTo(Path)} to order paths from different views.
     */
    final long serial = NEXT_SERIAL.getAndIncrement();

    /** The underlying {@link VfsRoot}. */
    private final VfsRoot root;

    /** Lazily constructed entry and directory index for archive and module roots. */
    private volatile @Nullable Index index;

    /** True once {@link #close()} has been called on this view. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Open handles created through this view. None of their classes overrides {@code equals}, so the set holds each
     * one by identity. It is a concurrent set for the reason given on {@code VfsRoot.openHandles}.
     */
    private final Set<Closeable> openHandles = ConcurrentHashMap.newKeySet();

    /**
     * Owned {@link Vfs} and provider registration if created by {@link VfsFileSystemProvider}, or {@code null} for
     * a borrowed view from {@link VfsRoot#asFileSystem()}.
     */
    private final @Nullable Ownership ownership;

    /** Regex metacharacters that must be escaped when converting a glob to a {@link Pattern}. */
    private static final String REGEX_METACHARACTERS = "\\*?[]{}()+|^$.";

    /**
     * Entry lookup table and synthesized directory hierarchy for an archive or module root.
     *
     * @param entriesByName
     *            canonical entries keyed by root-relative path.
     * @param childNamesByDir
     *            sorted child names keyed by parent directory path ({@code ""} for the root directory).
     */
    private record Index(Map<String, VfsEntry> entriesByName, Map<String, List<String>> childNamesByDir) {
    }

    /**
     * Resources owned by a standalone filesystem created via {@link VfsFileSystemProvider}.
     *
     * @param vfs
     *            the dedicated {@link Vfs} instance to close when the filesystem closes.
     * @param registeredPath
     *            the registration key in {@link VfsFileSystemProvider}, or {@code null} if unregistered.
     */
    record Ownership(Vfs vfs, @Nullable String registeredPath) {
    }

    /**
     * Constructor.
     *
     * @param root
     *            the root to view as a filesystem.
     * @param ownership
     *            the owned {@link Vfs} and registration key, or {@code null} for a borrowed root.
     */
    VfsFileSystem(final VfsRoot root, final @Nullable Ownership ownership) {
        this.root = root;
        this.ownership = ownership;
    }

    /**
     * Return the underlying {@link VfsRoot}.
     *
     * @return the root.
     */
    VfsRoot getRoot() {
        return root;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Throw {@link ClosedFileSystemException} if this filesystem view or its root is closed.
     *
     * @throws ClosedFileSystemException
     *             if closed.
     */
    void ensureOpen() {
        if (!isOpen()) {
            throw new ClosedFileSystemException();
        }
    }

    /**
     * Register an open handle with this view and its root, closing it and throwing
     * {@link ClosedFileSystemException} if either has already closed.
     *
     * @param <T>
     *            the handle type.
     * @param handle
     *            the newly opened handle.
     * @return {@code handle}.
     * @throws IOException
     *             if closing {@code handle} on registration failure fails.
     * @throws ClosedFileSystemException
     *             if this view or its root is closed.
     */
    <T extends Closeable> T register(final T handle) throws IOException {
        openHandles.add(handle);
        // The handle is added before the view is checked to be open, and close() marks the view closed before it
        // copies the set, so if the view is still open here, close() will see the handle and close it. Otherwise
        // the handle is closed here, and if close() also closes it, the second close does nothing
        if (isOpen() && root.trackOpenHandle(handle)) {
            return handle;
        }
        openHandles.remove(handle);
        handle.close();
        throw new ClosedFileSystemException();
    }

    /**
     * Unregister a closed handle from this view and its root.
     *
     * @param handle
     *            the closed handle.
     */
    void unregister(final Closeable handle) {
        openHandles.remove(handle);
        root.untrackOpenHandle(handle);
    }

    /**
     * Open an {@link InputStream} for {@code entry} tracked by both this view and its root.
     *
     * @param entry
     *            the entry to open.
     * @return the opened stream.
     * @throws IOException
     *             if the entry cannot be opened.
     */
    InputStream openInputStream(final VfsEntry entry) throws IOException {
        final var stream = entry.open();
        try {
            return register(new ViewInputStream(stream, this, entry.getPath()));
        } catch (final RuntimeException | Error e) {
            try {
                stream.close();
            } catch (final IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * An {@link InputStream} tracked by a {@link VfsFileSystem} view that rejects reads after the view or stream is
     * closed.
     */
    private static final class ViewInputStream extends InputStream {
        /** Open stream state, cleared atomically on close. */
        private final AtomicReference<@Nullable OpenStream> state;

        /** Whether the underlying stream supports {@link #mark(int)}. */
        private final boolean supportsMark;

        /** The entry path for error messages. */
        private final String path;

        /** Exception message used for reads after closure. */
        private volatile String closedMessage = "Stream closed";

        /**
         * The open stream's resources.
         *
         * @param delegate
         *            the root's stream.
         * @param owner
         *            the owning view.
         */
        private record OpenStream(InputStream delegate, VfsFileSystem owner) {
        }

        /**
         * Constructor.
         *
         * @param delegate
         *            the root's stream.
         * @param owner
         *            the owning view.
         * @param path
         *            the reported entry path.
         */
        ViewInputStream(final InputStream delegate, final VfsFileSystem owner, final String path) {
            this.path = path;
            supportsMark = delegate.markSupported();
            state = new AtomicReference<>(new OpenStream(delegate, owner));
        }

        /**
         * Capture the open delegate for a read.
         *
         * @return the delegate.
         * @throws IOException
         *             if the stream has closed.
         */
        private InputStream delegate() throws IOException {
            final var open = state.get();
            if (open == null) {
                throw new IOException(closedMessage);
            }
            return open.delegate();
        }

        @Override
        public int read() throws IOException {
            return delegate().read();
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            return delegate().read(bytes, offset, length);
        }

        @Override
        public long skip(final long count) throws IOException {
            return delegate().skip(count);
        }

        @Override
        public int available() throws IOException {
            return delegate().available();
        }

        @Override
        public synchronized void mark(final int readlimit) {
            final var open = state.get();
            if (open != null) {
                open.delegate().mark(readlimit);
            }
        }

        @Override
        public synchronized void reset() throws IOException {
            delegate().reset();
        }

        @Override
        public boolean markSupported() {
            return supportsMark;
        }

        @Override
        public void close() throws IOException {
            final var current = state.get();
            if (current != null) {
                closedMessage = "Cannot read " + path
                        + (current.owner().getRoot().isClosed() ? " after the root has been closed"
                                : " after the stream has been closed");
            }
            final var open = state.getAndSet(null);
            if (open != null) {
                try {
                    open.delegate().close();
                } finally {
                    open.owner().unregister(this);
                }
            }
        }
    }

    /**
     * Return the entry and directory index, building it on first use.
     *
     * @return the index.
     * @throws ClosedFileSystemException
     *             if this filesystem view is closed.
     * @throws IOException
     *             if the root's entries cannot be listed.
     */
    private Index index() throws IOException {
        // Directory listings and aggregate file-store statistics need the index. Point lookups use the root's
        // direct lookup instead, so reading one file does not enumerate the entire root.
        ensureOpen();
        var idx = index;
        if (idx != null) {
            return idx;
        }
        synchronized (this) {
            idx = index;
            if (idx != null) {
                return idx;
            }
            final Map<String, VfsEntry> entriesByName = new LinkedHashMap<>();
            final Map<String, TreeSet<String>> childNames = new HashMap<>();
            childNames.put("", new TreeSet<>());
            for (final var entry : root.getEntries()) {
                final var name = entry.getPathFromRoot();
                // A name that is not canonical cannot be looked up, since a path is normalized before it is looked
                // up, and indexing one would list a directory named "." or "..", which a walk descends into without
                // end, as each listing of it normalizes back to the same directory. getEntries() reports each name
                // only once, so putIfAbsent only guards against a root that reported a name twice
                if (!VfsRoot.isCanonicalEntryName(name) || entriesByName.putIfAbsent(name, entry) != null) {
                    continue;
                }
                // Add this entry to its parent directory, then that directory to its own parent, and so on up to
                // the root, stopping as soon as a directory is reached that has already been added
                var currName = name;
                var simpleName = entry.getLastSegment();
                while (true) {
                    if (simpleName.isEmpty()) {
                        break;
                    }
                    final var slashIdx = currName.lastIndexOf('/');
                    final var dirName = slashIdx < 0 ? "" : currName.substring(0, slashIdx);
                    final var children = childNames.computeIfAbsent(dirName, k -> new TreeSet<>());
                    if (!children.add(simpleName) || dirName.isEmpty()) {
                        break;
                    }
                    currName = dirName;
                    simpleName = PathSyntax.simpleName(currName);
                }
            }
            final Map<String, List<String>> childNamesByDir = new HashMap<>();
            childNames.forEach((dirName, children) -> childNamesByDir.put(dirName, List.copyOf(children)));
            index = idx = new Index(Map.copyOf(entriesByName), Map.copyOf(childNamesByDir));
            if (!isOpen()) {
                // A close raced with this build, and may have dropped the index before this method published it,
                // so drop it here rather than leave every entry of the root reachable from a closed view
                index = null;
                throw new ClosedFileSystemException();
            }
            return idx;
        }
    }

    /**
     * Look up a file entry by root-relative path.
     *
     * @param name
     *            the root-relative path without a leading {@code '/'}.
     * @return the file entry, or {@code null} if not found or if {@code name} names a directory.
     * @throws IOException
     *             if the lookup fails.
     */
    @Nullable
    VfsEntry entry(final String name) throws IOException {
        ensureOpen();
        return root instanceof DirRoot dirRoot ? dirRoot.fileEntryForNio(name) : root.getEntry(name);
    }

    /**
     * Return whether {@code name} names a directory in this filesystem.
     *
     * @param name
     *            the root-relative path without a leading {@code '/'} ({@code ""} for the root directory).
     * @return {@code true} if {@code name} is a directory.
     * @throws IOException
     *             if the lookup fails.
     */
    boolean isDirectory(final String name) throws IOException {
        ensureOpen();
        if (root instanceof DirRoot dirRoot) {
            final var path = dirRoot.pathForNio(name);
            return path != null && Files.isDirectory(path);
        }
        if (name.isEmpty()) {
            return true;
        }
        return index().childNamesByDir().containsKey(name);
    }

    /**
     * Return whether {@code name} names an existing file or directory in this filesystem.
     *
     * @param name
     *            the root-relative path without a leading {@code '/'}.
     * @return {@code true} if the file or directory exists.
     * @throws IOException
     *             if the lookup fails.
     */
    boolean exists(final String name) throws IOException {
        return entry(name) != null || isDirectory(name);
    }

    /**
     * Return the sorted simple names of the immediate children of directory {@code name}.
     *
     * @param name
     *            the root-relative directory path without a leading {@code '/'}.
     * @return the sorted child names, or {@code null} if {@code name} is not a directory.
     * @throws IOException
     *             if listing the directory fails.
     */
    @Nullable
    List<String> childNames(final String name) throws IOException {
        ensureOpen();
        if (root instanceof DirRoot dirRoot) {
            final var path = dirRoot.pathForNio(name);
            if (path == null || !Files.isDirectory(path)) {
                return null;
            }
            final List<String> names = new ArrayList<>();
            try (var listing = Files.newDirectoryStream(path)) {
                for (final var child : listing) {
                    names.add(child.getFileName().toString());
                }
            }
            names.sort(String::compareTo);
            return List.copyOf(names);
        }
        return index().childNamesByDir().get(name);
    }

    /**
     * Return the underlying storage {@link URI} ({@code file:}, {@code jar:}, or {@code jrt:}) for {@code path}.
     *
     * @param path
     *            the path.
     * @return the storage {@link URI}.
     */
    URI uriOf(final VfsPath path) {
        final var name = path.entryName();
        if (!name.isEmpty()) {
            try {
                final var entry = entry(name);
                if (entry != null) {
                    return entry.getURI();
                }
            } catch (final IOException e) {
                throw new IOError(e);
            }
        }
        // The path is the root directory, another directory, or something this filesystem does not contain, so
        // there is no VfsEntry to ask -- have the root name it the way it names its entries. The root directory of
        // a view of a package root is the package root, not the top of the jarfile.
        final var packageRoot = root.getPackageRoot();
        final var uri = root
                .uriOfPath(packageRoot.isEmpty() ? name : name.isEmpty() ? packageRoot : packageRoot + "/" + name);
        // The default filesystem ends the URI of a directory with '/', but a directory is named here without one,
        // as it is within a jarfile, so that the URI does not depend on whether the name is a directory. The root
        // directory is left as the root names it, since the top of a jarfile, "jar:...!/", has to keep its '/'.
        final var uriStr = uri.toString();
        return name.isEmpty() || !uriStr.endsWith("/") ? uri : URI.create(uriStr.substring(0, uriStr.length() - 1));
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public VfsFileSystemProvider provider() {
        return VfsFileSystemProvider.instance();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Closes this filesystem view and any streams, channels, or directory streams opened through it. If this view
     * was obtained via {@link VfsRoot#asFileSystem()}, the underlying {@link VfsRoot} and {@link Vfs} remain open;
     * if it was created as a standalone filesystem via {@link VfsFileSystemProvider}, closing it also unregisters
     * it and closes the dedicated {@link Vfs} backing it. Calling this method on an already-closed view has no
     * effect.
     *
     * @throws IOException
     *             if an open handle fails to close.
     */
    @Override
    public void close() throws IOException {
        // Stop the root handing out this view before anything else happens, so that a caller cannot be given a
        // filesystem that has already started closing. This cannot fail, so the rest of the close is still reached
        root.discardFileSystemView(this);
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // The view is marked closed before the set is copied (see register). A handle added after the copy closes
        // itself, so clearing the set cannot lose a handle that needs closing
        final List<Closeable> handlesToClose = new ArrayList<>(openHandles);
        openHandles.clear();
        // The index holds every entry of the root, so it is dropped rather than left reachable from a view that
        // can no longer be read through. An index that is being built concurrently is dropped by index() itself,
        // which re-checks after publishing it
        index = null;
        // Close outside the tracking lock: each handle unregisters itself, and a concurrent root close may be
        // closing the same handle. All close implementations are idempotent.
        Throwable failure = null;
        for (final var handle : handlesToClose) {
            try {
                handle.close();
            } catch (final Throwable e) {
                if (failure == null) {
                    failure = e;
                } else if (e instanceof VirtualMachineError && !(failure instanceof VirtualMachineError)) {
                    e.addSuppressed(failure);
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        // A borrowed view leaves its root and Vfs open, but still owns the handles opened through the view.
        final var owned = ownership;
        if (owned != null) {
            // Free the name before the close can be observed as finished.
            final var registeredPath = owned.registeredPath();
            if (registeredPath != null) {
                VfsFileSystemProvider.unregister(this, registeredPath);
            }
            owned.vfs().close();
        }
        if (failure instanceof final IOException io) {
            throw io;
        } else if (failure instanceof final RuntimeException runtime) {
            throw runtime;
        } else if (failure instanceof final Error error) {
            throw error;
        } else if (failure != null) {
            throw new IOException(failure);
        }
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && !root.isClosed();
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(getPath("/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of(fileStore());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Only the {@code "basic"} file attribute view is supported.
     */
    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(final String first, final String... more) {
        Assert.notNull(first, "first");
        Assert.notNullElements(more, "more");
        if (more.length == 0) {
            return VfsPath.parse(this, first);
        }
        final var joined = new StringBuilder(first);
        for (final var name : more) {
            if (!name.isEmpty()) {
                if (joined.length() > 0 && joined.charAt(joined.length() - 1) != '/') {
                    joined.append('/');
                }
                joined.append(name);
            }
        }
        return VfsPath.parse(this, joined.toString());
    }

    @Override
    public PathMatcher getPathMatcher(final String syntaxAndPattern) {
        Assert.notNull(syntaxAndPattern, "syntaxAndPattern");
        final var colonIdx = syntaxAndPattern.indexOf(':');
        if (colonIdx <= 0) {
            throw new IllegalArgumentException("Expected a pattern of the form syntax:pattern");
        }
        final var syntax = syntaxAndPattern.substring(0, colonIdx);
        final var pattern = syntaxAndPattern.substring(colonIdx + 1);
        final Pattern compiled;
        // FileSystem#getPathMatcher specifies that the syntax is compared without regard to case
        if ("glob".equalsIgnoreCase(syntax)) {
            compiled = globToPattern(pattern);
        } else if ("regex".equalsIgnoreCase(syntax)) {
            compiled = Pattern.compile(pattern);
        } else {
            throw new UnsupportedOperationException("Unsupported pattern syntax: " + syntax);
        }
        return path -> compiled.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("A virtual filesystem has no user principals");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("A virtual filesystem cannot be watched for changes");
    }

    @Override
    public String toString() {
        return root.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Compile a glob into a regular expression, as specified by {@link FileSystem#getPathMatcher(String)}.
     *
     * @param glob
     *            the glob.
     * @return the equivalent regular expression.
     */
    private static Pattern globToPattern(final String glob) {
        final var regex = new StringBuilder("^");
        var inGroup = false;
        for (var i = 0; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            switch (c) {
            case '\\':
                if (++i == glob.length()) {
                    throw new PatternSyntaxException("No character to escape", glob, i - 1);
                }
                appendLiteral(regex, glob.charAt(i));
                break;
            case '[':
                i = appendBracketExpression(regex, glob, i);
                break;
            case '{':
                if (inGroup) {
                    throw new PatternSyntaxException("Groups cannot be nested", glob, i);
                }
                regex.append("(?:(?:");
                inGroup = true;
                break;
            case '}':
                if (inGroup) {
                    regex.append("))");
                    inGroup = false;
                } else {
                    appendLiteral(regex, c);
                }
                break;
            case ',':
                if (inGroup) {
                    regex.append(")|(?:");
                } else {
                    appendLiteral(regex, c);
                }
                break;
            case '*':
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // "**" crosses directory boundaries, "*" does not
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
                break;
            case '?':
                regex.append("[^/]");
                break;
            default:
                appendLiteral(regex, c);
                break;
            }
        }
        if (inGroup) {
            throw new PatternSyntaxException("Unclosed group", glob, glob.length());
        }
        return Pattern.compile(regex.append('$').toString());
    }

    /**
     * Append the bracket expression that starts at {@code startIdx} in a glob to a regular expression.
     *
     * @param regex
     *            the regular expression being built.
     * @param glob
     *            the glob.
     * @param startIdx
     *            the index of the {@code '['} in the glob.
     * @return the index of the {@code ']'} that closed the bracket expression.
     */
    private static int appendBracketExpression(final StringBuilder regex, final String glob, final int startIdx) {
        // Intersected with everything but the name separator, so that a negated bracket expression does not match
        // across a directory boundary any more than "*" and "?" do, which is how the default provider compiles it
        regex.append("[[^/]&&[");
        var i = startIdx + 1;
        if (i < glob.length() && glob.charAt(i) == '^') {
            // '!' is the negation in a glob, so a leading '^' is a literal, as it is for the default provider
            regex.append("\\^");
            i++;
        } else if (i < glob.length() && glob.charAt(i) == '!') {
            regex.append('^');
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == ']') {
            // A ']' at the start of a bracket expression is a literal
            regex.append("\\]");
            i++;
        }
        for (; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            if (c == ']') {
                regex.append("]]");
                return i;
            }
            if (c == '/') {
                throw new PatternSyntaxException("'/' is not allowed within a bracket expression", glob, i);
            }
            if (c == '\\' || c == '[' || c == '&' || c == '^') {
                regex.append('\\');
            }
            regex.append(c);
        }
        throw new PatternSyntaxException("Unclosed bracket expression", glob, startIdx);
    }

    /**
     * Append a character to a regular expression, escaping it if it is a regex metacharacter.
     *
     * @param regex
     *            the regular expression being built.
     * @param c
     *            the character.
     */
    private static void appendLiteral(final StringBuilder regex, final char c) {
        if (REGEX_METACHARACTERS.indexOf(c) >= 0) {
            regex.append('\\');
        }
        regex.append(c);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The single file store of this filesystem. */
    private final FileStore fileStore = new VfsFileStore();

    /**
     * Returns the single file store of this filesystem.
     *
     * @return the file store.
     */
    FileStore fileStore() {
        return fileStore;
    }

    /** The single, read-only file store of a {@link VfsFileSystem}. */
    private final class VfsFileStore extends FileStore {
        /** Constructor. */
        private VfsFileStore() {
            // Empty
        }

        @Override
        public String name() {
            return root.getPath();
        }

        @Override
        public String type() {
            return root.fileStoreType();
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public long getTotalSpace() throws IOException {
            var total = 0L;
            for (final var entry : index().entriesByName().values()) {
                total += Math.max(0L, entry.getLength());
            }
            return total;
        }

        @Override
        public long getUsableSpace() {
            return 0L;
        }

        @Override
        public long getUnallocatedSpace() {
            return 0L;
        }

        @Override
        public boolean supportsFileAttributeView(final Class<? extends FileAttributeView> type) {
            return type == BasicFileAttributeView.class;
        }

        @Override
        public boolean supportsFileAttributeView(final String name) {
            return "basic".equals(name);
        }

        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(final Class<V> type) {
            return null;
        }

        @Override
        public Object getAttribute(final String attribute) {
            throw new UnsupportedOperationException("Unsupported file store attribute: " + attribute);
        }

        @Override
        public String toString() {
            return name();
        }
    }
}
