package io.github.classgraph.vfs.internal;

import java.lang.reflect.InvocationTargetException;
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
        if (path instanceof VfsPath vfsPath) {
            return vfsPath.toStorageUri();
        }
        if (isClassGraphPath(path)) {
            // A plugin's copy has the same public API but a different class identity. Do not cache its Method or
            // Class here: a strong cache would keep its classloader alive after all roots are closed.
            try {
                return (URI) path.getClass().getMethod("toStorageUri").invoke(path);
            } catch (final NoSuchMethodException ignored) {
                // Older copies predate URI separation: their toUri() already returns the storage URI.
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("Cannot access the virtual path's storage URI", e);
            } catch (final InvocationTargetException e) {
                final var cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Cannot obtain the virtual path's storage URI", cause);
            }
        }
        return URLPaths.toURI(path);
    }

    /**
     * Recognize this loader's paths and isolated copies of the same public path class.
     *
     * @param path
     *            the path to identify.
     * @return whether it is a ClassGraph virtual path.
     */
    private static boolean isClassGraphPath(final Path path) {
        return path instanceof VfsPath || path.getClass().getName().equals(VfsPath.class.getName());
    }

    /**
     * Return the normalized storage name used for root and archive reporting.
     *
     * @param path
     *            the path.
     * @return the normalized storage name.
     */
    public static String pathStr(final Path path) {
        if (!isClassGraphPath(path)) {
            return FileUtils.pathStr(path);
        }
        final var uri = toURI(path).toString();
        final var resolved = FastPathResolver.resolve(uri);
        return uri.endsWith("!/") && !resolved.endsWith("/") ? resolved + "!/" : resolved;
    }
}
