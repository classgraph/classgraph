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
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

/**
 * The {@link FileSystemProvider} of the read-only {@link VfsFileSystem} views handed out by
 * {@link VfsRoot#asFileSystem()}.
 *
 * <p>
 * This provider is not installed in the JVM, and is not reachable through {@link java.nio.file.FileSystems}: a
 * virtual filesystem is always reached from the {@link VfsRoot} it is a view of, because a {@link Vfs} has to be
 * told how to open a root before there is anything to address.
 *
 * <p>
 * Every method that reads a path's filesystem throws {@link java.nio.file.ClosedFileSystemException} once that
 * filesystem, or the {@link Vfs} behind it, has been closed. The purely syntactic {@link Path} methods go on
 * working, since they need nothing from the filesystem's content.
 */
final class VfsFileSystemProvider extends FileSystemProvider {
    /** The single instance of this provider. */
    static final VfsFileSystemProvider INSTANCE = new VfsFileSystemProvider();

    /** Constructor. */
    private VfsFileSystemProvider() {
    }

    /**
     * Cast a path to a {@link VfsPath}.
     *
     * @param path
     *            the path.
     * @return the path, as a {@link VfsPath}.
     * @throws ProviderMismatchException
     *             if the path did not come from a {@link VfsFileSystem}.
     */
    private static VfsPath check(final Path path) {
        if (!(path instanceof final VfsPath vfsPath)) {
            throw new ProviderMismatchException("Not a path of a virtual filesystem: " + path);
        }
        return vfsPath;
    }

    /**
     * Look up the entry a path names.
     *
     * @param path
     *            the path.
     * @return the entry.
     * @throws java.nio.file.FileSystemException
     *             if the path names a directory, which has no content to read.
     * @throws NoSuchFileException
     *             if the path does not name a file of the filesystem.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private static VfsEntry entryOf(final VfsPath path) throws IOException {
        final var fileSystem = path.getFileSystem();
        final var name = path.entryName();
        final var entry = fileSystem.entry(name);
        if (entry == null) {
            // Reported the same way as the default provider reports it, rather than as a missing file, since
            // Files#exists and Files#isDirectory both answer for a directory
            if (fileSystem.isDirectory(name)) {
                throw new FileSystemException(path.toString(), null, "Is a directory");
            }
            throw new NoSuchFileException(path.toString());
        }
        return entry;
    }

    /**
     * Check that a set of open options asks only to read.
     *
     * @param options
     *            the open options.
     * @throws ReadOnlyFileSystemException
     *             if an option asks to write.
     */
    private static void checkReadOnly(final Collection<? extends OpenOption> options) {
        for (final var option : options) {
            if (option == StandardOpenOption.WRITE || option == StandardOpenOption.APPEND
                    || option == StandardOpenOption.CREATE || option == StandardOpenOption.CREATE_NEW
                    || option == StandardOpenOption.DELETE_ON_CLOSE
                    || option == StandardOpenOption.TRUNCATE_EXISTING || option == StandardOpenOption.SYNC
                    || option == StandardOpenOption.DSYNC) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String getScheme() {
        return "vfs";
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException
     *             always: a virtual filesystem is created by {@link VfsRoot#asFileSystem()}, not by URI.
     */
    @Override
    public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "A virtual filesystem cannot be created from a URI; call Vfs#open and then VfsRoot#asFileSystem");
    }

    /**
     * {@inheritDoc}
     *
     * @throws FileSystemNotFoundException
     *             always: a virtual filesystem is reached from the {@link VfsRoot} it is a view of, not by URI.
     */
    @Override
    public FileSystem getFileSystem(final URI uri) {
        throw new FileSystemNotFoundException(
                "A virtual filesystem cannot be looked up by URI; call Vfs#open and then VfsRoot#asFileSystem");
    }

    /**
     * {@inheritDoc}
     *
     * @throws FileSystemNotFoundException
     *             always: a virtual filesystem is reached from the {@link VfsRoot} it is a view of, not by URI.
     */
    @Override
    public Path getPath(final URI uri) {
        throw new FileSystemNotFoundException(
                "A virtual filesystem cannot be looked up by URI; call Vfs#open and then VfsRoot#asFileSystem");
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
        // Arrays.asList rather than Set.of, because a repeated open option is accepted, not rejected
        checkReadOnly(Arrays.asList(options));
        return entryOf(check(path)).open();
    }

    @Override
    public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options,
            final FileAttribute<?>... attrs) throws IOException {
        checkReadOnly(options);
        return new VfsByteChannel(entryOf(check(path)).read());
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(final Path dir, final Filter<? super Path> filter)
            throws IOException {
        final var dirPath = check(dir);
        final var fileSystem = dirPath.getFileSystem();
        final var name = dirPath.entryName();
        // A name can be a file and a directory at the same time, if the archive holds both "a/b" and "a/b/c". The
        // file wins, so that a name is never reported as a file by Files#isDirectory and listed as a directory
        // here at the same time
        if (fileSystem.entry(name) != null) {
            throw new NotDirectoryException(dir.toString());
        }
        final var childNames = fileSystem.childNames(name);
        if (childNames == null) {
            throw new NoSuchFileException(dir.toString());
        }
        final List<Path> children = new ArrayList<>(childNames.size());
        for (final var childName : childNames) {
            final var child = dir.resolve(fileSystem.getPath(childName));
            if (filter.accept(child)) {
                children.add(child);
            }
        }
        return new VfsDirectoryStream(children);
    }

    /**
     * A {@link DirectoryStream} over a list of child paths that was built up front, so nothing is held open. It
     * hands out its iterator once, as {@link DirectoryStream#iterator()} requires.
     */
    private static final class VfsDirectoryStream implements DirectoryStream<Path> {
        /** The children of the directory. */
        private final List<Path> children;

        /** Whether {@link #iterator()} has been called, or this stream has been closed. */
        private boolean spent;

        /**
         * Constructor.
         *
         * @param children
         *            the children of the directory.
         */
        VfsDirectoryStream(final List<Path> children) {
            this.children = children;
        }

        @Override
        public Iterator<Path> iterator() {
            if (spent) {
                throw new IllegalStateException("The iterator has already been returned, or the stream was closed");
            }
            spent = true;
            // Collections#unmodifiableList so that the iterator does not support remove
            return Collections.unmodifiableList(children).iterator();
        }

        @Override
        public void close() {
            spent = true;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public void createDirectory(final Path dir, final FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(final Path path) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(final Path source, final Path target, final CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(final Path source, final Path target, final CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void setAttribute(final Path path, final String attribute, final Object value,
            final LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public boolean isSameFile(final Path path, final Path path2) {
        final var vfsPath = check(path);
        vfsPath.getFileSystem().ensureOpen();
        // A path of another filesystem is answered, not rejected, as FileSystemProvider#isSameFile requires
        if (!(path2 instanceof final VfsPath vfsPath2)) {
            return false;
        }
        return vfsPath.toAbsolutePath().normalize().equals(vfsPath2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(final Path path) {
        check(path).getFileSystem().ensureOpen();
        return false;
    }

    @Override
    public FileStore getFileStore(final Path path) {
        final var fileSystem = check(path).getFileSystem();
        fileSystem.ensureOpen();
        return fileSystem.fileStore();
    }

    @Override
    public void checkAccess(final Path path, final AccessMode... modes) throws IOException {
        final var vfsPath = check(path);
        for (final var mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(path.toString(), null,
                        "A virtual filesystem is read-only and holds no executable files");
            }
        }
        if (!vfsPath.getFileSystem().exists(vfsPath.entryName())) {
            throw new NoSuchFileException(path.toString());
        }
    }

    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
        if (type != BasicFileAttributeView.class) {
            return null;
        }
        final var vfsPath = check(path);
        return type.cast(new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return attributesOf(vfsPath);
            }

            @Override
            public void setTimes(final @Nullable FileTime lastModifiedTime, final @Nullable FileTime lastAccessTime,
                    final @Nullable FileTime createTime) {
                throw new ReadOnlyFileSystemException();
            }
        });
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(final Path path, final Class<A> type,
            final LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("Unsupported file attributes type: " + type.getName());
        }
        return type.cast(attributesOf(check(path)));
    }

    @Override
    public Map<String, Object> readAttributes(final Path path, final String attributes, final LinkOption... options)
            throws IOException {
        final var colonIdx = attributes.indexOf(':');
        final var view = colonIdx < 0 ? "basic" : attributes.substring(0, colonIdx);
        if (!"basic".equals(view)) {
            throw new UnsupportedOperationException("Unsupported file attribute view: " + view);
        }
        final var requested = attributes.substring(colonIdx + 1);
        final var attrs = attributesOf(check(path));
        final var names = "*".equals(requested) ? ATTRIBUTE_NAMES : List.of(requested.split(","));
        final Map<String, Object> selected = new LinkedHashMap<>();
        for (final var name : names) {
            // Only the attributes that were asked for are read, because reading the size of a module entry means
            // reading the whole entry
            selected.put(name, attributeOf(attrs, name));
        }
        return selected;
    }

    /** The names of the attributes of the {@code "basic"} view, in the order the default provider lists them. */
    private static final List<String> ATTRIBUTE_NAMES = List.of("lastModifiedTime", "lastAccessTime",
            "creationTime", "size", "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey");

    /**
     * Read one named attribute of the {@code "basic"} view.
     *
     * @param attrs
     *            the attributes of the file.
     * @param name
     *            the name of the attribute.
     * @return the value of the attribute.
     * @throws IllegalArgumentException
     *             if the {@code "basic"} view has no attribute of that name.
     */
    private static @Nullable Object attributeOf(final BasicFileAttributes attrs, final String name) {
        return switch (name) {
        case "lastModifiedTime" -> attrs.lastModifiedTime();
        case "lastAccessTime" -> attrs.lastAccessTime();
        case "creationTime" -> attrs.creationTime();
        case "size" -> attrs.size();
        case "isRegularFile" -> attrs.isRegularFile();
        case "isDirectory" -> attrs.isDirectory();
        case "isSymbolicLink" -> attrs.isSymbolicLink();
        case "isOther" -> attrs.isOther();
        case "fileKey" -> attrs.fileKey();
        default -> throw new IllegalArgumentException("Unknown file attribute: " + name);
        };
    }

    /**
     * Read the attributes of a path.
     *
     * @param path
     *            the path.
     * @return the attributes.
     * @throws NoSuchFileException
     *             if the path names nothing in the filesystem.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private static BasicFileAttributes attributesOf(final VfsPath path) throws IOException {
        final var name = path.entryName();
        final var fileSystem = path.getFileSystem();
        final var entry = fileSystem.entry(name);
        if (entry == null) {
            if (!fileSystem.isDirectory(name)) {
                throw new NoSuchFileException(path.toString());
            }
            return new VfsFileAttributes(null);
        }
        return new VfsFileAttributes(entry);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The attributes of one file or directory of a virtual filesystem.
     *
     * @param entry
     *            the entry, or null if this is a directory synthesized from the names of the entries below it.
     */
    private record VfsFileAttributes(@Nullable VfsEntry entry) implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            // A directory of a virtual filesystem has no modification time of its own, and neither does a module
            // entry, both of which report the epoch. A time before the epoch is negative, and is a real time that
            // a file of a directory root can carry, so it is reported as it is rather than clamped to the epoch.
            return FileTime.fromMillis(entry == null ? 0L : entry.getLastModifiedMillis());
        }

        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime();
        }

        @Override
        public FileTime creationTime() {
            return lastModifiedTime();
        }

        @Override
        public boolean isRegularFile() {
            return entry != null;
        }

        @Override
        public boolean isDirectory() {
            return entry == null;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            if (entry == null) {
                return 0L;
            }
            final var length = entry.getLength();
            if (length >= 0) {
                return length;
            }
            // A module entry does not know its length without reading it. This is only paid by a caller that
            // actually asks for the size, not by every walk of the filesystem.
            try (var content = entry.read()) {
                final var byteBuffer = content.getByteBuffer();
                return byteBuffer == null ? 0L : byteBuffer.remaining();
            } catch (final IOException e) {
                throw new IOError(e);
            }
        }

        @Override
        public @Nullable Object fileKey() {
            return entry;
        }
    }

    /** A read-only {@link SeekableByteChannel} over the content of one {@link VfsEntry}. */
    private static final class VfsByteChannel implements SeekableByteChannel {
        /** The content of the entry, closed when this channel is closed. */
        private final CloseableByteBuffer content;

        /** The content of the entry. */
        private final ByteBuffer buffer;

        /**
         * The read position, which is allowed to be beyond the end of the content, where reads return end-of-file.
         * It is tracked separately from the position of {@link #buffer}, which cannot exceed its limit.
         */
        private long position;

        /** Whether this channel is still open. */
        private final AtomicBoolean open = new AtomicBoolean(true);

        /**
         * Constructor.
         *
         * @param content
         *            the content of the entry.
         * @throws IOException
         *             if the content could not be read.
         */
        VfsByteChannel(final CloseableByteBuffer content) throws IOException {
            this.content = content;
            final var byteBuffer = content.getByteBuffer();
            if (byteBuffer == null) {
                content.close();
                throw new IOException("Could not read entry content");
            }
            // Slice rather than duplicate, so that the content starts at index 0 whatever position the buffer
            // arrived at, which is what the absolute indexing in read() and size() assumes
            this.buffer = byteBuffer.slice();
        }

        /**
         * Check that this channel is still open.
         *
         * @throws java.nio.channels.ClosedChannelException
         *             if it is not.
         */
        private void checkOpen() throws IOException {
            if (!open.get()) {
                throw new java.nio.channels.ClosedChannelException();
            }
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            checkOpen();
            if (position >= buffer.limit()) {
                return -1;
            }
            final var numBytes = Math.min(dst.remaining(), buffer.limit() - (int) position);
            final var slice = buffer.slice((int) position, numBytes);
            try {
                dst.put(slice);
            } catch (final IllegalStateException e) {
                // The buffer aliases a memory mapping that was unmapped by closing the Vfs while this read was
                // in flight -- fail the same documented way as a read through a closed FileChannel
                throw new IOException("Cannot read a file that has been unmapped by closing the Vfs", e);
            }
            position += numBytes;
            return numBytes;
        }

        @Override
        public int write(final ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            checkOpen();
            return position;
        }

        @Override
        public SeekableByteChannel position(final long newPosition) throws IOException {
            checkOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("Negative position: " + newPosition);
            }
            // Seeking beyond the end is allowed, the position reads back as the one that was asked for, and reads
            // there return -1
            position = newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            checkOpen();
            return buffer.limit();
        }

        @Override
        public SeekableByteChannel truncate(final long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() throws IOException {
            if (open.getAndSet(false)) {
                content.close();
            }
        }
    }
}
