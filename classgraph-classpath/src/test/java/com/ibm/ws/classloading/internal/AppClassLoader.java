package com.ibm.ws.classloading.internal;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere Liberty's {@code AppClassLoader}, which serves an application's classes from the
 * {@code SmartClassPath} held in its {@code smartClassPath} field, and delegates to the classloaders of the
 * libraries the application is configured to use.
 */
public class AppClassLoader extends ClassLoader {
    /** The {@code SmartClassPath} of the application, or null if the application has none. */
    public final @Nullable Object smartClassPath;

    /** The classloaders of the libraries that are searched before the application's own classpath. */
    public final List<ClassLoader> beforeAppDelegateLoaders = new ArrayList<>();

    /** The classloaders of the libraries that are searched after the application's own classpath. */
    public final List<ClassLoader> afterAppDelegateLoaders = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param smartClassPath
     *            the {@code SmartClassPath} of the application, or null if the application has none.
     * @param parent
     *            the parent classloader.
     */
    public AppClassLoader(final @Nullable Object smartClassPath, final @Nullable ClassLoader parent) {
        super(parent);
        this.smartClassPath = smartClassPath;
    }

    /**
     * Add the classloader of a library that is searched before the application's own classpath.
     *
     * @param delegate
     *            the library's classloader.
     * @return this, for chaining.
     */
    public AppClassLoader delegatingBeforeAppTo(final ClassLoader delegate) {
        beforeAppDelegateLoaders.add(delegate);
        return this;
    }

    /**
     * Add the classloader of a library that is searched after the application's own classpath.
     *
     * @param delegate
     *            the library's classloader.
     * @return this, for chaining.
     */
    public AppClassLoader delegatingAfterAppTo(final ClassLoader delegate) {
        afterAppDelegateLoaders.add(delegate);
        return this;
    }
}
