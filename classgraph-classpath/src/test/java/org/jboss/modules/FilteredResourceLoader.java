package org.jboss.modules;

import java.net.URI;

/**
 * Stand-in for JBoss' {@code FilteredResourceLoader}, which wraps another resource loader and serves only the
 * resources of it that pass a filter. It has no root or jarfile of its own -- the only thing it exposes is the
 * location of the resource loader it delegates to.
 */
public class FilteredResourceLoader {
    /** The location of the resource loader that this resource loader delegates to. */
    private final URI location;

    /**
     * Constructor.
     *
     * @param location
     *            the location of the resource loader that this resource loader delegates to.
     */
    public FilteredResourceLoader(final URI location) {
        this.location = location;
    }

    /**
     * The location of the resource loader that this resource loader delegates to.
     *
     * @return the location.
     */
    public URI getLocation() {
        return location;
    }
}
