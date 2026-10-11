package io.github.classgraph.vfs.internal.zip;

import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * A {@link FileSystemProvider} for a URI scheme that no URL stream handler is registered for, so that a URI with
 * this scheme can be turned into a {@link Path} but not into a {@link java.net.URL}. It is installed through
 * {@code META-INF/services} in the test resources. {@code "cgprovideronly:<rest>"} names the file that
 * {@code "file:<rest>"} names, so every {@link Path} it returns is in the default filesystem, and no other method
 * of this provider is ever called.
 */
public final class ProviderOnlySchemeFileSystemProvider extends FileSystemProvider {
    /** The URI scheme of this provider. */
    public static final String SCHEME = "cgprovideronly";

    /**
     * Returns the URI with this provider's scheme that names a file in the default filesystem.
     *
     * @param path
     *            a path in the default filesystem.
     * @return the URI.
     */
    public static URI uriOf(final Path path) {
        return URI.create(SCHEME + ":" + path.toUri().getRawSchemeSpecificPart());
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public Path getPath(final URI uri) {
        return Path.of(URI.create("file:" + uri.getRawSchemeSpecificPart()));
    }

    @Override
    public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileSystem getFileSystem(final URI uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options,
            final FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(final Path dir,
            final DirectoryStream.Filter<? super Path> filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createDirectory(final Path dir, final FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(final Path path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copy(final Path source, final Path target, final CopyOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void move(final Path source, final Path target, final CopyOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSameFile(final Path path, final Path path2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isHidden(final Path path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileStore getFileStore(final Path path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkAccess(final Path path, final AccessMode... modes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(final Path path, final Class<A> type,
            final LinkOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> readAttributes(final Path path, final String attributes,
            final LinkOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(final Path path, final String attribute, final Object value,
            final LinkOption... options) {
        throw new UnsupportedOperationException();
    }
}
