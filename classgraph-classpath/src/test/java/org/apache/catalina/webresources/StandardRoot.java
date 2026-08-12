package org.apache.catalina.webresources;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for Catalina's {@code StandardRoot}, the {@code WebResourceRoot} implementation that a
 * {@code WebappClassLoaderBase} serves resources through. Only the two members that ClassGraph reads are modelled:
 * the {@code getBaseUrls()} method, and the {@code allResources} field, which holds the five {@code WebResourceSet}
 * lists (pre, main, class, jar and post resources).
 */
public class StandardRoot {
    /** The URLs returned by {@code getBaseUrls()}. */
    private final List<URL> baseUrls = new ArrayList<>();

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
     * Add a URL to be returned by {@code getBaseUrls()}.
     *
     * @param url
     *            the URL.
     * @return this, for chaining.
     */
    public StandardRoot addBaseUrl(final URL url) {
        baseUrls.add(url);
        return this;
    }

    /**
     * The base URLs of the webapp.
     *
     * @return the base URLs.
     */
    public List<URL> getBaseUrls() {
        return baseUrls;
    }
}
