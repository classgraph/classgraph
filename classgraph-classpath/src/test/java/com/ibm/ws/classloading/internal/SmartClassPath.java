package com.ibm.ws.classloading.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere Liberty's {@code SmartClassPath}, which reports an application's classpath either from its
 * {@code getClassPath()} method, or, on the Liberty versions that do not implement that method, from the list of
 * containers in its {@code classPath} field.
 */
public class SmartClassPath {
    /** What {@code getClassPath()} returns, or null if it throws {@link UnsupportedOperationException}. */
    private final @Nullable Collection<Object> classPathURLs;

    /** The containers that make up the classpath. */
    public final List<Object> classPath = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param classPathURLs
     *            what {@code getClassPath()} returns, or null if it throws {@link UnsupportedOperationException},
     *            as Liberty's own implementation does for some containers.
     */
    public SmartClassPath(final @Nullable Collection<Object> classPathURLs) {
        this.classPathURLs = classPathURLs;
    }

    /**
     * Add a container to the classpath.
     *
     * @param container
     *            the container.
     * @return this, for chaining.
     */
    public SmartClassPath addContainer(final Object container) {
        classPath.add(container);
        return this;
    }

    /**
     * The URLs that make up the classpath.
     *
     * @return the classpath URLs.
     */
    public Collection<Object> getClassPath() {
        if (classPathURLs == null) {
            throw new UnsupportedOperationException();
        }
        return classPathURLs;
    }
}
