package io.github.classgraph.vfs.internal;

import java.net.URI;
import java.nio.file.Path;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.VfsPath;

/** Storage naming for paths, explicitly unwrapping ClassGraph's virtual filesystem views. */
public final class StoragePaths {
    /** Prevent construction. */
    private StoragePaths() {
        // Static methods only.
    }

    /**
     * Return a storage URI without substituting a virtual view's provider URI.
     *
     * @param path
     *            the path.
     * @return its storage URI.
     */
    public static URI toURI(final Path path) {
        return path instanceof VfsPath vfsPath ? vfsPath.toStorageUri() : URLPaths.toURI(path);
    }

    /**
     * Return the normalized storage name used for root and archive reporting.
     *
     * @param path
     *            the path.
     * @return the normalized storage name.
     */
    public static String pathStr(final Path path) {
        if (!(path instanceof VfsPath)) {
            return FileUtils.pathStr(path);
        }
        final var uri = toURI(path).toString();
        final var resolved = FastPathResolver.resolve(uri);
        return uri.endsWith("!/") && !resolved.endsWith("/") ? resolved + "!/" : resolved;
    }
}
