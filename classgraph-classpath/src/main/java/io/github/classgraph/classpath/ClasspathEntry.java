/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.classpath;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsRoot;

/**
 * A single element of the classpath (a directory or jarfile from which classes and resources are loaded).
 *
 * <p>
 * Each entry preserves the representation in which it was provided by its classloader or caller (path string,
 * {@link File}, {@link Path}, {@link URL}, or {@link URI}). Call {@link #open(Vfs)} to open the entry in its
 * underlying representation:
 *
 * <pre>
 * try (Classpath classpath = new ClasspathFinder().enableClasspath().find()) {
 *     Vfs vfs = classpath.getVfs();
 *     for (ClasspathEntry entry : classpath) {
 *         VfsRoot root = entry.open(vfs);
 *         for (VfsEntry resource : root) {
 *             System.out.println(resource.getPath());
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * This is a sealed class with five permitted subclasses corresponding to each entry representation:
 * {@link OfPathString}, {@link OfFile}, {@link OfPath}, {@link OfURL}, and {@link OfURI}.
 *
 * <pre>
 * if (entry instanceof ClasspathEntry.OfURL urlEntry) {
 *     System.out.println("Served over " + urlEntry.getURL().getProtocol());
 * }
 * </pre>
 */
public abstract sealed class ClasspathEntry {
    /** The normalized location of the classpath element. */
    private final String location;

    /** The {@link Object#toString()} of the classloader this element was obtained from, or null if none. */
    private final @Nullable String classLoaderName;

    /** The package root directory prefixes within this classpath element. */
    private final List<String> packageRootPrefixes;

    /** The automatic lib directory prefixes within this classpath element. */
    private final List<String> libDirPrefixes;

    /**
     * Constructor.
     *
     * @param location
     *            the normalized location of the classpath element.
     * @param classLoaderName
     *            the string representation of the originating classloader, or null if none.
     * @param packageRootPrefixes
     *            the package root directory prefixes within this classpath element.
     * @param libDirPrefixes
     *            the automatic lib directory prefixes within this classpath element.
     */
    private ClasspathEntry(final String location, final @Nullable String classLoaderName,
            final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
        this.location = location;
        this.classLoaderName = classLoaderName;
        // These come from a ClassLoaderHandler, which is public API, and are handed straight back out as
        // unmodifiable lists and read by equals and hashCode. The classpath order already copies and normalizes
        // them when the handler hands them over, so the copy here only guards against a caller that passes a list
        // that can change. (Copying an immutable list returns it unchanged, so it costs nothing in the usual case.)
        this.packageRootPrefixes = List.copyOf(packageRootPrefixes);
        this.libDirPrefixes = List.copyOf(libDirPrefixes);
    }

    /**
     * Creates a {@link ClasspathEntry} subclass matching the runtime type of {@code classpathEntryObj}.
     *
     * @param classpathEntryObj
     *            the raw classpath entry object.
     * @param location
     *            the normalized location of the classpath element.
     * @param classLoaderName
     *            the string representation of the originating classloader, or null if none.
     * @param packageRootPrefixes
     *            the package root directory prefixes within this classpath element.
     * @param libDirPrefixes
     *            the automatic lib directory prefixes within this classpath element.
     * @return the created {@link ClasspathEntry}.
     */
    static ClasspathEntry of(final Object classpathEntryObj, final String location,
            final @Nullable String classLoaderName, final List<String> packageRootPrefixes,
            final List<String> libDirPrefixes) {
        if (classpathEntryObj instanceof File) {
            return new OfFile(new File(location), location, classLoaderName, packageRootPrefixes, libDirPrefixes);
        }
        if (classpathEntryObj instanceof final Path path) {
            return new OfPath(localPath(path, location), location, classLoaderName, packageRootPrefixes,
                    libDirPrefixes);
        }
        if (classpathEntryObj instanceof final URL url) {
            return new OfURL(url, location, classLoaderName, packageRootPrefixes, libDirPrefixes);
        }
        if (classpathEntryObj instanceof final URI uri) {
            return new OfURI(uri, location, classLoaderName, packageRootPrefixes, libDirPrefixes);
        }
        // A path string, and anything else a classloader named an element with, which is read as a path
        return new OfPathString(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
    }

    /**
     * Resolves a default-filesystem {@link Path} to {@code location}, or returns {@code path} unchanged if it
     * belongs to a non-default filesystem.
     *
     * @param path
     *            the original path.
     * @param location
     *            the normalized location string.
     * @return the resolved {@link Path}.
     */
    private static Path localPath(final Path path, final String location) {
        if (path.getFileSystem() != FileSystems.getDefault()) {
            return path;
        }
        try {
            return Path.of(location);
        } catch (final InvalidPathException e) {
            // The location cannot be spelled as a path of the default filesystem, so it names nothing there, and
            // the path the classpath element was found as is the best that is left
            return path;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the normalized location of the classpath element: a canonical path with {@code '/'} separators for
     * local files and directories, {@code outer.jar!/inner.jar} syntax for nested jars, or a URL/URI string for
     * non-local resources.
     *
     * @return the location of the classpath element.
     */
    public String getLocation() {
        return location;
    }

    /**
     * Returns the {@link Object#toString()} of the {@link ClassLoader} from which this element was obtained, or
     * null if the element was supplied directly via {@link ClasspathFinder#enableClasspathEntries}.
     *
     * @return the string representation of the originating classloader, or null if none.
     */
    public @Nullable String getClassLoaderName() {
        return classLoaderName;
    }

    /**
     * Returns the relative directory prefixes (such as {@code "WEB-INF/classes/"}) declared by the originating
     * {@link ClassLoaderHandler#getPackageRootPrefixes()} as potential package roots within this element.
     *
     * @return the package root prefixes, as an unmodifiable list.
     */
    public @Unmodifiable List<String> getPackageRootPrefixes() {
        return packageRootPrefixes;
    }

    /**
     * Returns the relative directory prefixes (such as {@code "WEB-INF/lib/"}) declared by the originating
     * {@link ClassLoaderHandler#getLibDirPrefixes()} as potential automatic lib directories within this element.
     *
     * @return the lib directory prefixes, as an unmodifiable list.
     */
    public @Unmodifiable List<String> getLibDirPrefixes() {
        return libDirPrefixes;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Opens this classpath element as a {@link VfsRoot} using the given {@link Vfs}.
     *
     * <p>
     * Pass {@link Classpath#getVfs()} to reuse jarfiles that were already opened during classpath discovery. The
     * returned {@link VfsRoot} is owned by {@code vfs} and remains valid until {@code vfs} is closed.
     *
     * @param vfs
     *            the virtual filesystem used to open the classpath element.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the classpath element cannot be opened or read, or if {@code vfs} is closed.
     */
    public final VfsRoot open(final Vfs vfs) throws IOException {
        Assert.notNull(vfs, "vfs");
        return openImpl(vfs);
    }

    /**
     * Opens this classpath element in its underlying representation using {@code vfs}.
     *
     * @param vfs
     *            the virtual filesystem used to open the classpath element.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the classpath element cannot be opened or read, or if {@code vfs} is closed.
     */
    abstract VfsRoot openImpl(Vfs vfs) throws IOException;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Checks whether this entry is equal to another object (same subclass, location, classloader name, package root
     * prefixes, and lib directory prefixes).
     *
     * @param obj
     *            the object to compare with.
     * @return true if the two classpath elements are equal.
     */
    @Override
    public final boolean equals(final @Nullable Object obj) {
        return obj instanceof final ClasspathEntry other && getClass() == other.getClass()
                && location.equals(other.location) && Objects.equals(classLoaderName, other.classLoaderName)
                && packageRootPrefixes.equals(other.packageRootPrefixes)
                && libDirPrefixes.equals(other.libDirPrefixes);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), location, classLoaderName, packageRootPrefixes, libDirPrefixes);
    }

    /**
     * Returns {@link #getLocation()}, followed by {@code " [" + getClassLoaderName() + "]"} if associated with a
     * classloader.
     *
     * @return a string representation of this classpath element.
     */
    @Override
    public final String toString() {
        return classLoaderName == null ? location : location + " [" + classLoaderName + "]";
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A classpath element represented by a path or URL string.
     */
    public static final class OfPathString extends ClasspathEntry {
        /**
         * Constructor.
         *
         * @param location
         *            the normalized location of the classpath element.
         * @param classLoaderName
         *            the string representation of the originating classloader, or null.
         * @param packageRootPrefixes
         *            the package root directory prefixes within this element.
         * @param libDirPrefixes
         *            the automatic lib directory prefixes within this element.
         */
        OfPathString(final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
        }

        @Override
        VfsRoot openImpl(final Vfs vfs) throws IOException {
            return vfs.open(getLocation());
        }
    }

    /**
     * A classpath element represented by a {@link File}.
     */
    public static final class OfFile extends ClasspathEntry {
        /** The file. */
        private final File file;

        /**
         * Constructor.
         *
         * @param file
         *            the file representing the classpath element.
         * @param location
         *            the normalized location of the classpath element.
         * @param classLoaderName
         *            the string representation of the originating classloader, or null.
         * @param packageRootPrefixes
         *            the package root directory prefixes within this element.
         * @param libDirPrefixes
         *            the automatic lib directory prefixes within this element.
         */
        OfFile(final File file, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.file = file;
        }

        /**
         * Returns the {@link File} for this classpath element.
         *
         * @return the file.
         */
        public File getFile() {
            return file;
        }

        @Override
        VfsRoot openImpl(final Vfs vfs) throws IOException {
            return vfs.open(file);
        }
    }

    /**
     * A classpath element represented by a {@link Path}.
     */
    public static final class OfPath extends ClasspathEntry {
        /** The path. */
        private final Path path;

        /**
         * Constructor.
         *
         * @param path
         *            the path representing the classpath element.
         * @param location
         *            the normalized location of the classpath element.
         * @param classLoaderName
         *            the string representation of the originating classloader, or null.
         * @param packageRootPrefixes
         *            the package root directory prefixes within this element.
         * @param libDirPrefixes
         *            the automatic lib directory prefixes within this element.
         */
        OfPath(final Path path, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.path = path;
        }

        /**
         * Returns the {@link Path} for this classpath element (which may belong to a custom
         * {@link java.nio.file.FileSystem}).
         *
         * @return the path.
         */
        public Path getPath() {
            return path;
        }

        @Override
        VfsRoot openImpl(final Vfs vfs) throws IOException {
            return vfs.open(path);
        }
    }

    /**
     * A classpath element represented by a {@link URL}.
     */
    public static final class OfURL extends ClasspathEntry {
        /** The URL. */
        private final URL url;

        /**
         * Constructor.
         *
         * @param url
         *            the URL representing the classpath element.
         * @param location
         *            the normalized location of the classpath element.
         * @param classLoaderName
         *            the string representation of the originating classloader, or null.
         * @param packageRootPrefixes
         *            the package root directory prefixes within this element.
         * @param libDirPrefixes
         *            the automatic lib directory prefixes within this element.
         */
        OfURL(final URL url, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.url = url;
        }

        /**
         * Returns the {@link URL} for this classpath element.
         *
         * @return the URL.
         */
        public URL getURL() {
            return url;
        }

        @Override
        VfsRoot openImpl(final Vfs vfs) throws IOException {
            return vfs.open(url);
        }
    }

    /**
     * A classpath element represented by a {@link URI}.
     */
    public static final class OfURI extends ClasspathEntry {
        /** The URI. */
        private final URI uri;

        /**
         * Constructor.
         *
         * @param uri
         *            the URI representing the classpath element.
         * @param location
         *            the normalized location of the classpath element.
         * @param classLoaderName
         *            the string representation of the originating classloader, or null.
         * @param packageRootPrefixes
         *            the package root directory prefixes within this element.
         * @param libDirPrefixes
         *            the automatic lib directory prefixes within this element.
         */
        OfURI(final URI uri, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.uri = uri;
        }

        /**
         * Returns the {@link URI} for this classpath element.
         *
         * @return the URI.
         */
        public URI getURI() {
            return uri;
        }

        @Override
        VfsRoot openImpl(final Vfs vfs) throws IOException {
            return vfs.open(uri);
        }
    }
}
