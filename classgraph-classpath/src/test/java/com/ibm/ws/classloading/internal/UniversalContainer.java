package com.ibm.ws.classloading.internal;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere Liberty's {@code ContainerClassLoader$UniversalContainer}, one element of a
 * {@code SmartClassPath}. It reports the locations it serves resources from through {@code getContainerURLs()}, or,
 * if that returns nothing, through the {@code Container} in its {@code container} field.
 */
public class UniversalContainer {
    /** The URLs returned by {@code getContainerURLs()}. */
    private final Collection<Object> containerURLs;

    /** The container that this container wraps, or null if there is none. */
    public final @Nullable Object container;

    /**
     * Constructor.
     *
     * @param containerURLs
     *            the URLs returned by {@code getContainerURLs()}.
     * @param container
     *            the container that this container wraps, or null if there is none.
     */
    public UniversalContainer(final Collection<Object> containerURLs, final @Nullable Object container) {
        this.containerURLs = containerURLs;
        this.container = container;
    }

    /**
     * Constructor for a container that reports nothing itself, and has to be read through the container it wraps.
     *
     * @param container
     *            the container that this container wraps.
     */
    public UniversalContainer(final Object container) {
        this(List.of(), container);
    }

    /**
     * The URLs of the locations that contribute to this container.
     *
     * @return the URLs.
     */
    public Collection<Object> getContainerURLs() {
        return containerURLs;
    }
}
