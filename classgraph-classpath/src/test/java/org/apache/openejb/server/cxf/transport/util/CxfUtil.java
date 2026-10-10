package org.apache.openejb.server.cxf.transport.util;

/**
 * Stand-in for TomEE's {@code CxfUtil}, a class of the container. The parent of a {@code CxfContainerClassLoader}
 * is the classloader that this class is loaded by.
 */
public final class CxfUtil {
    /** Not instantiable. */
    private CxfUtil() {
    }
}
