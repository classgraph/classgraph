package org.apache.openejb.server.cxf.transport.util;

/**
 * Stand-in for TomEE's {@code CxfContainerClassLoader}, whose private {@code tccl()} method returns the calling
 * webapp's classloader. Here {@code tccl()} returns whatever object it is given, so that a test can make it return
 * something other than a {@link ClassLoader}, as a container release whose internals have changed might.
 */
public class CxfContainerClassLoader extends ClassLoader {
    /** The object that {@code tccl()} returns. */
    private final Object tccl;

    /**
     * Constructor.
     *
     * @param tccl
     *            the object that {@code tccl()} returns.
     * @param parent
     *            the parent classloader.
     */
    public CxfContainerClassLoader(final Object tccl, final ClassLoader parent) {
        super(parent);
        this.tccl = tccl;
    }

    /**
     * The classloader of the calling webapp, which the real class loads classes through.
     *
     * @return the object given to the constructor.
     */
    @SuppressWarnings("unused")
    private Object tccl() {
        return tccl;
    }
}
