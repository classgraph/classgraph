package com.ibm.ws.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere traditional's {@code ExtClassLoader}, which extends {@link URLClassLoader} and
 * additionally reports a classpath of its own through {@code getClassPath()}.
 */
public class ExtClassLoader extends URLClassLoader {
    /** The extension classpath, or null if the classloader has none. */
    private final @Nullable String classPath;

    /**
     * Constructor.
     *
     * @param classPath
     *            the extension classpath, or null if the classloader has none.
     * @param urls
     *            the URLs that this classloader loads classes from.
     */
    public ExtClassLoader(final @Nullable String classPath, final URL... urls) {
        super(urls, /* parent = */ null);
        this.classPath = classPath;
    }

    /**
     * The extension classpath.
     *
     * @return the classpath, or null if the classloader has none.
     */
    public @Nullable String getClassPath() {
        return classPath;
    }
}
