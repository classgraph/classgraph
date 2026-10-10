package org.apache.catalina.webresources;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for Catalina's {@code StandardRoot}, the {@code WebResourceRoot} implementation that a
 * {@code WebappClassLoaderBase} serves resources through. Only the {@code allResources} field, which holds the five
 * {@code WebResourceSet} lists (pre, main, class, jar and post resources), and the {@code getBaseUrls()} method are
 * modelled.
 */
public class StandardRoot {
    /** The {@code WebResourceSet}s, grouped as Catalina groups them. */
    public final List<List<Object>> allResources = new ArrayList<>();

    /**
     * Add a group of {@code WebResourceSet}s, as Catalina's {@code allResources} holds them.
     *
     * @param webResourceSets
     *            the {@code WebResourceSet}s in the group.
     * @return this, for chaining.
     */
    public StandardRoot addResourceSets(final Object... webResourceSets) {
        allResources.add(new ArrayList<>(List.of(webResourceSets)));
        return this;
    }

    /**
     * The URL of the directory or archive of every resource set that serves static content, as Catalina computes
     * them. These include the webapp's own directory or WAR file, so most of what they hold is not on the
     * classpath.
     *
     * @return the base URLs.
     */
    public List<URL> getBaseUrls() {
        final List<URL> baseUrls = new ArrayList<>();
        for (final List<Object> webResourceSets : allResources) {
            for (final Object webResourceSet : webResourceSets) {
                if (webResourceSet instanceof final AbstractResourceSet resourceSet
                        && !resourceSet.getClassLoaderOnly() && resourceSet.getBaseUrl() != null) {
                    baseUrls.add(resourceSet.getBaseUrl());
                }
            }
        }
        return baseUrls;
    }
}
