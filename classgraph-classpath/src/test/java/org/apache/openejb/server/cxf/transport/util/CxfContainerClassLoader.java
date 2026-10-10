package org.apache.openejb.server.cxf.transport.util;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for TomEE's {@code CxfContainerClassLoader}, the classloader that CXF runs a webapp's web service calls
 * in. Like the real class, it has no classpath of its own: its parent is the container's classloader, which
 * {@link CxfUtil} is loaded by, and it loads every class through the classloader of the webapp that is calling CXF
 * on the current thread, or through the container's classloader if no webapp is.
 */
public class CxfContainerClassLoader extends ClassLoader {
    /** The classloader of the webapp that is calling CXF on this thread. */
    private final ThreadLocal<@Nullable ClassLoader> tccl = new ThreadLocal<>();

    /**
     * Constructor.
     *
     * @param containerLoader
     *            the container's classloader. The real class uses the classloader of {@link CxfUtil}, which the
     *            test has to pass in, because it loads {@link CxfUtil} in a classloader of its own.
     */
    public CxfContainerClassLoader(final ClassLoader containerLoader) {
        super(containerLoader);
    }

    /**
     * Set the classloader of the webapp that is calling CXF on this thread.
     *
     * @param loader
     *            the webapp's classloader.
     */
    public void tccl(final ClassLoader loader) {
        tccl.set(loader);
    }

    /**
     * The classloader that classes are loaded through. This is private in TomEE, and is called reflectively.
     *
     * @return the classloader of the webapp that is calling CXF on this thread, or the container's classloader if
     *         no webapp is.
     */
    private @Nullable ClassLoader tccl() {
        final var current = tccl.get();
        return current != null ? current : getParent();
    }

    // Like the real class, a class is never looked for in the parent first. The real class looks for CXF's own
    // classes, in the org.apache.cxf package, in the container's classloader before the webapp's; the test loads
    // none of those.
    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        final var loader = tccl();
        if (loader == null) {
            throw new ClassNotFoundException(name);
        }
        return loader.loadClass(name);
    }
}
