package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code EmptyResourceSet}, which serves no resources from the filesystem, and so
 * contributes no classpath entry.
 */
public class EmptyResourceSet {
    /**
     * The path within this resource set that resources are served from.
     *
     * @return the internal path.
     */
    public String getInternalPath() {
        return "";
    }
}
