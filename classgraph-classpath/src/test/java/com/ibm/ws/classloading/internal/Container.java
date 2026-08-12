package com.ibm.ws.classloading.internal;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for the WebSphere Liberty artifact API's {@code Container}, which reports the locations on disk that
 * contribute to it through {@code getURLs()}, or, on older Liberty versions that do not implement that method,
 * through its {@code delegate}.
 */
public class Container {
    /** The URLs returned by {@code getURLs()}. */
    private final Collection<Object> urls;

    /** The delegate that the container's location has to be read from if {@code getURLs()} returns nothing. */
    public final @Nullable Object delegate;

    /**
     * Constructor.
     *
     * @param urls
     *            the URLs returned by {@code getURLs()}.
     * @param delegate
     *            the delegate that the container's location has to be read from if {@code getURLs()} returns
     *            nothing, or null if there is none.
     */
    public Container(final Collection<Object> urls, final @Nullable Object delegate) {
        this.urls = urls;
        this.delegate = delegate;
    }

    /**
     * Constructor for a container that reports nothing itself, and has to be read through its delegate.
     *
     * @param delegate
     *            the delegate.
     */
    public Container(final Object delegate) {
        this(List.of(), delegate);
    }

    /**
     * The URLs of the locations on disk that contribute to this container.
     *
     * @return the URLs.
     */
    public Collection<Object> getURLs() {
        return urls;
    }
}
