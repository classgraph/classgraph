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

import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * A read-only {@link FileSystem} view of a {@link VfsRoot}, so that a directory, a jarfile, a jarfile nested inside
 * another jarfile, a package root, a jarfile that exists only in RAM, or a module can all be read through
 * {@link java.nio.file.Files} and {@link Path}.
 *
 * <p>
 * The filesystem separator is {@code '/'} for every kind of root, and the root directory is {@code "/"}.
 */
final class VfsFileSystem extends FileSystem {
    /** The root this is a view of. */
    private final VfsRoot root;

    /** The directory index, built on first use. */
    private volatile @Nullable Index index;

    /** True once {@link #close()} has been called on this view. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** The regex metacharacters that have to be escaped when a glob is compiled into a {@link Pattern}. */
    private static final String REGEX_METACHARACTERS = "\\*?[]{}()+|^$.";

    /**
     * The entries of a {@link VfsRoot}, indexed by name, plus the directory tree that is implied by those names.
     * {@link VfsRoot#getEntries()} lists files only, since a jarfile need not contain an entry for a directory
     * whose contents it holds, so the directories are synthesized from the entry names.
     *
     * @param entriesByName
     *            every entry of the root, keyed by name. Where two entries share a name, the first wins, which is
     *            the same rule {@link VfsRoot#getEntry(String)} uses.
     * @param childNamesByDir
     *            the sorted simple names of the children of each directory, keyed by directory name. The root
     *            directory is the empty string, and other directory names have no trailing {@code '/'}.
     */
    private record Index(Map<String, VfsEntry> entriesByName, Map<String, List<String>> childNamesByDir) {
    }

    /**
     * Constructor.
     *
     * @param root
     *            the root to view as a filesystem.
     */
    VfsFileSystem(final VfsRoot root) {
        this.root = root;
    }

    /**
     * Returns the root this is a view of.
     *
     * @return the root.
     */
    VfsRoot getRoot() {
        return root;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the directory index, building it on first use.
     *
     * @return the index.
     * @throws ClosedFileSystemException
     *             if this filesystem has been closed.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private Index index() throws IOException {
        // Every read of this filesystem's content goes through the index, including reads through a Path of it,
        // so this is the one place that has to turn away access after a close
        if (!isOpen()) {
            throw new ClosedFileSystemException();
        }
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
                final var name = entry.getName();
                if (name.isEmpty() || entriesByName.putIfAbsent(name, entry) != null) {
                    // Where two entries share a name, the first one wins
                    continue;
                }
                // Add this entry to its parent directory, then that directory to its own parent, and so on up to
                // the root, stopping as soon as a directory is reached that has already been added
                var currName = name;
                while (true) {
                    final var slashIdx = currName.lastIndexOf('/');
                    final var dirName = slashIdx < 0 ? "" : currName.substring(0, slashIdx);
                    final var simpleName = currName.substring(slashIdx + 1);
                    if (simpleName.isEmpty()) {
                        break;
                    }
                    final var children = childNames.computeIfAbsent(dirName, k -> new TreeSet<>());
                    if (!children.add(simpleName) || dirName.isEmpty()) {
                        break;
                    }
                    currName = dirName;
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
     * Look up an entry by name.
     *
     * @param name
     *            the entry name, relative to the root, with no leading {@code '/'}.
     * @return the entry, or null if there is no file with that name (in particular, if the name is a directory).
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    @Nullable
    VfsEntry entry(final String name) throws IOException {
        return index().entriesByName().get(name);
    }

    /**
     * Returns whether a name is a directory of this filesystem.
     *
     * @param name
     *            the name, relative to the root, with no leading {@code '/'}. The root directory is the empty
     *            string.
     * @return true if the name is a directory.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    boolean isDirectory(final String name) throws IOException {
        return index().childNamesByDir().containsKey(name);
    }

    /**
     * Returns whether a name is a file or a directory of this filesystem.
     *
     * @param name
     *            the name, relative to the root, with no leading {@code '/'}.
     * @return true if the name exists.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    boolean exists(final String name) throws IOException {
        return isDirectory(name) || entry(name) != null;
    }

    /**
     * Returns the sorted simple names of the children of a directory.
     *
     * @param name
     *            the directory name, relative to the root, with no leading {@code '/'}.
     * @return the simple names of the children, or null if the name is not a directory.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    @Nullable
    List<String> childNames(final String name) throws IOException {
        return index().childNamesByDir().get(name);
    }

    /**
     * Returns the URI of a path of this filesystem.
     *
     * @param path
     *            the path.
     * @return the URI of the underlying storage the path names, which is a {@code file:}, {@code jar:} or
     *         {@code jrt:} URI, not a URI of this filesystem's own provider.
     */
    URI uriOf(final VfsPath path) {
        final var name = path.entryName();
        if (name.isEmpty()) {
            return root.getURI();
        }
        try {
            final var entry = entry(name);
            if (entry != null) {
                return entry.getURI();
            }
        } catch (final IOException e) {
            throw new IOError(e);
        }
        // The path names a directory, or something this filesystem does not contain, so there is no VfsEntry to
        // ask -- form the URI the same way an entry would
        final var packageRoot = root.getPackageRoot();
        final var entryPath = URLPaths.encodePath(packageRoot.isEmpty() ? name : packageRoot + "/" + name);
        final var rootURIStr = root.getURI().toString();
        final String uriStr;
        if (root.getKind() == VfsRoot.Kind.DIRECTORY || rootURIStr.startsWith("jrt:")) {
            uriStr = rootURIStr + (rootURIStr.endsWith("/") ? "" : "/") + entryPath;
        } else {
            uriStr = (rootURIStr.startsWith("jar:") ? "" : "jar:") + rootURIStr + "!/" + entryPath;
        }
        return URI.create(uriStr);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public VfsFileSystemProvider provider() {
        return VfsFileSystemProvider.INSTANCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This closes only this view, so every subsequent access to the filesystem, or to a {@link Path} of it, throws
     * {@link ClosedFileSystemException}. The {@link VfsRoot} it is a view of goes on working, since other callers
     * may be reading through it -- a {@link Vfs} hands the same root to everything that opens the same path -- and
     * the next call to {@link VfsRoot#asFileSystem()} builds a new view rather than handing out this closed one.
     *
     * <p>
     * It releases no file handle, memory mapping or temporary file: those belong to the {@link Vfs} behind the
     * root, so call {@link Vfs#close()} to release them. It does drop the directory index this view built, which
     * holds every entry of the root.
     *
     * <p>
     * Closing an already-closed filesystem has no effect.
     */
    @Override
    public void close() {
        // Stop the root handing out this view before anything else happens, so that a caller cannot be given a
        // filesystem that has already started closing. This cannot fail, so the rest of the close is still reached
        root.discardFileSystemView(this);
        closed.set(true);
        // The index holds every entry of the root, so it is dropped rather than left reachable from a view that
        // can no longer be read through. An index that is being built concurrently is dropped by index() itself,
        // which re-checks after publishing it
        index = null;
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
        if ("glob".equals(syntax)) {
            compiled = globToPattern(pattern);
        } else if ("regex".equals(syntax)) {
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
        regex.append('[');
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
                regex.append(']');
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
        @Override
        public String name() {
            return root.getPath();
        }

        @Override
        public String type() {
            return root.getKind().toString().toLowerCase(java.util.Locale.ROOT);
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
            return type == java.nio.file.attribute.BasicFileAttributeView.class;
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
