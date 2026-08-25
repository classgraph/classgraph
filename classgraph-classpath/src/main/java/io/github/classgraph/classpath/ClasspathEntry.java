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

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsRoot;
import org.jspecify.annotations.Nullable;

/**
 * One element of the classpath: a directory or a jarfile that classes and resources are loaded from.
 *
 * <p>
 * A classloader names its classpath elements in whichever form it happens to store them -- a path string, a
 * {@link File}, a {@link Path}, a {@link URL} or a {@link URI} -- and each is kept in the form it was found in,
 * rather than being flattened to a string and parsed again. {@link #open(Vfs)} opens the element in whichever of
 * those forms it has, so code that reads the classpath does not have to know which one it is:
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
 * For the code that does need to know, this is a sealed type with one subclass per form -- {@link OfPathString},
 * {@link OfFile}, {@link OfPath}, {@link OfURL} and {@link OfURI} -- and each subclass hands the element back in
 * that form. Because the type is sealed, those five are the only possibilities, so a {@code switch} over them on
 * Java 21 or later is checked for exhaustiveness:
 *
 * <pre>
 * if (entry instanceof ClasspathEntry.OfURL urlEntry) {
 *     System.out.println("Served over " + urlEntry.getURL().getProtocol());
 * }
 * </pre>
 */
public abstract sealed class ClasspathEntry {
    /** The location of the classpath element. */
    private final String location;

    /** The string form of the classloader this classpath element was obtained from, or null if unknown. */
    private final @Nullable String classLoaderName;

    /** The directory prefixes that should be looked for within this classpath element. */
    private final List<String> packageRootPrefixes;

    /** The lib dirs whose jarfiles should be added to the classpath, within this classpath element. */
    private final List<String> libDirPrefixes;

    /**
     * Constructor.
     *
     * @param location
     *            the location of the classpath element.
     * @param classLoaderName
     *            the string form of the classloader this classpath element was obtained from, or null if unknown.
     * @param packageRootPrefixes
     *            the directory prefixes that should be looked for within this classpath element.
     * @param libDirPrefixes
     *            the lib dirs whose jarfiles should be added to the classpath, within this classpath element.
     */
    private ClasspathEntry(final String location, final @Nullable String classLoaderName,
            final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
        this.location = location;
        this.classLoaderName = classLoaderName;
        // Copied, since these come from a ClassLoaderHandler, which is public API, and are handed straight back out
        // as unmodifiable lists and read by equals and hashCode. (Copying an immutable list returns it unchanged,
        // so a handler that hands over a List#of costs nothing here.)
        this.packageRootPrefixes = List.copyOf(packageRootPrefixes);
        this.libDirPrefixes = List.copyOf(libDirPrefixes);
    }

    /**
     * Wrap a classpath element in the subclass that matches the form it was found in.
     *
     * @param classpathEntryObj
     *            the object the classpath element was found as.
     * @param location
     *            the location of the classpath element.
     * @param classLoaderName
     *            the string form of the classloader this classpath element was obtained from, or null if unknown.
     * @param packageRootPrefixes
     *            the directory prefixes that should be looked for within this classpath element.
     * @param libDirPrefixes
     *            the lib dirs whose jarfiles should be added to the classpath, within this classpath element.
     * @return the classpath element.
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
     * Respell a {@link Path} in the default filesystem with the location of the classpath element, so that a
     * classpath element that a classloader named with a relative path is opened under the same name that it is
     * reported by, rather than being opened a second time under the name it was found as. A {@link Path} in any
     * other filesystem is left alone: only the {@link Path} itself reaches such a filesystem, and the location
     * merely names it.
     *
     * @param path
     *            the path the classpath element was found as.
     * @param location
     *            the location of the classpath element.
     * @return the path to open the classpath element with.
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
     * Returns the location of the classpath element. This is an absolute path for a local directory or jarfile,
     * with {@code '/'} as the separator on every platform. A jarfile nested inside another jarfile is written in
     * the Java {@code outer.jar!/inner.jar} form. Anything that is not a local file, for example a classpath
     * element served over HTTP by a container, is the URL or URI it was found as. The location is not checked, so
     * it may name a directory or jarfile that does not exist.
     *
     * <p>
     * This is a name to report the classpath element by. To read it, call {@link #open(Vfs)}, which opens it in the
     * form it was found in rather than by parsing this string again.
     *
     * @return the location of the classpath element.
     */
    public String getLocation() {
        return location;
    }

    /**
     * Returns the {@link Object#toString()} of the classloader this classpath element was obtained from, or null if
     * it did not come from a classloader (for example, an entry from the {@code java.class.path} system property,
     * or from an overridden classpath). Only the string is kept, so that finding the classpath does not keep a
     * classloader alive.
     *
     * @return the string form of the classloader, or null if this element did not come from one.
     */
    public @Nullable String getClassLoaderName() {
        return classLoaderName;
    }

    /**
     * Returns the directory prefixes that should be looked for within this classpath element and stripped if
     * present, because a classloader of this type can place the root of the package hierarchy below them, for
     * example {@code "BOOT-INF/classes/"} for a Spring Boot jar. These are the layouts that the classloader could
     * have used, not the ones this classpath element actually uses, so a prefix is listed whether or not the
     * element contains a directory with that name. The list is empty for a classloader whose classpath elements
     * always have their classes at the root, and never contains the empty string.
     *
     * @return the package root prefixes, as an unmodifiable list.
     */
    public List<String> getPackageRootPrefixes() {
        return packageRootPrefixes;
    }

    /**
     * Returns the lib dirs whose jarfiles are added to the classpath if they are present within this classpath
     * element, because a classloader of this type loads from them without listing them as classpath elements, for
     * example {@code "BOOT-INF/lib/"} for a Spring Boot jar. These are the lib dirs the classloader could load
     * from, not the ones this classpath element actually has, so a lib dir is listed whether or not the element
     * contains a directory with that name. The list is empty for a classloader that lists every jarfile it loads
     * from, and never contains the empty string.
     *
     * @return the lib dir prefixes, as an unmodifiable list.
     */
    public List<String> getLibDirPrefixes() {
        return libDirPrefixes;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open this classpath element for reading, through the given virtual filesystem. A directory, a jarfile, and a
     * jarfile nested inside another jarfile are all opened the same way, and are read through the same
     * {@link VfsRoot} methods afterwards.
     *
     * <p>
     * The element is opened in the form the classloader named it with, so a {@link Path} is opened through its own
     * {@link java.nio.file.FileSystem}, rather than being written out as a string and parsed back.
     *
     * <p>
     * Pass {@link Classpath#getVfs()}, to read a jarfile that was already opened to read its manifest without
     * opening it a second time. Close the returned root when finished with it; closing the {@link Vfs} closes it
     * too, so a root does not outlive the {@link Vfs} it was opened through.
     *
     * @param vfs
     *            the virtual filesystem to open the classpath element through.
     * @return the opened root.
     * @throws IOException
     *             if the classpath element could not be opened or read, or if the {@link Vfs} has been closed. A
     *             classloader may name a jarfile or directory that is not there, so this is an expected outcome
     *             rather than a sign that something is wrong.
     */
    public final VfsRoot open(final Vfs vfs) throws IOException {
        Assert.notNull(vfs, "vfs");
        return openImpl(vfs);
    }

    /**
     * Open this classpath element, in the form it was found in.
     *
     * @param vfs
     *            the virtual filesystem to open the classpath element through.
     * @return the opened root.
     * @throws IOException
     *             if the classpath element could not be opened or read, or if the {@link Vfs} has been closed.
     */
    abstract VfsRoot openImpl(Vfs vfs) throws IOException;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Two classpath elements are equal if they were found in the same form, at the same {@link #getLocation()},
     * through the same classloader, with the same {@link #getPackageRootPrefixes()} and
     * {@link #getLibDirPrefixes()}.
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
     * Returns the {@link #getLocation()}, followed by the classloader it was obtained from in square brackets if it
     * came from one.
     *
     * @return the classpath element, as a string.
     */
    @Override
    public final String toString() {
        return classLoaderName == null ? location : location + " [" + classLoaderName + "]";
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A classpath element that a classloader named with a path string. This is the usual case: the entries of the
     * {@code java.class.path} system property, and of a jarfile manifest's {@code Class-Path} attribute, are path
     * strings. The path is the {@link #getLocation()}, so there is no separate accessor for it.
     */
    public static final class OfPathString extends ClasspathEntry {
        /**
         * Constructor.
         *
         * @param location
         *            the path of the classpath element, which is also its location.
         * @param classLoaderName
         *            the string form of the classloader this classpath element was obtained from, or null.
         * @param packageRootPrefixes
         *            the directory prefixes that should be looked for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this element.
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
     * A classpath element that a classloader named with a {@link File}.
     */
    public static final class OfFile extends ClasspathEntry {
        /** The file. */
        private final File file;

        /**
         * Constructor.
         *
         * @param file
         *            the file the classpath element was found as.
         * @param location
         *            the location of the classpath element.
         * @param classLoaderName
         *            the string form of the classloader this classpath element was obtained from, or null.
         * @param packageRootPrefixes
         *            the directory prefixes that should be looked for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this element.
         */
        OfFile(final File file, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.file = file;
        }

        /**
         * Returns this classpath element as a {@link File}, spelled with its {@link #getLocation()}, which on
         * Windows means {@link File#getPath()} comes back with backslashes.
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
     * A classpath element that a classloader named with a {@link Path}.
     */
    public static final class OfPath extends ClasspathEntry {
        /** The path. */
        private final Path path;

        /**
         * Constructor.
         *
         * @param path
         *            the path the classpath element was found as.
         * @param location
         *            the location of the classpath element.
         * @param classLoaderName
         *            the string form of the classloader this classpath element was obtained from, or null.
         * @param packageRootPrefixes
         *            the directory prefixes that should be looked for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this element.
         */
        OfPath(final Path path, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.path = path;
        }

        /**
         * Returns this classpath element as a {@link Path}. It may be in a filesystem other than the default one,
         * in which case opening the {@link Path} is what reaches it. Its {@link #getLocation()} is the
         * {@link Path#toUri()} form, which reaches the same element only if that filesystem's provider is installed
         * and the filesystem is still open, and only if the {@link Vfs} has not denied its URL scheme.
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
     * A classpath element that a classloader named with a {@link URL}, or with a {@link URI} or {@link Path} that
     * had a URL scheme. A {@code "jar:"} or {@code "file:"} URL names something in the local filesystem; with any
     * other scheme, {@link #open(Vfs)} reads it if the JVM has a handler for that scheme and the {@link Vfs} has
     * not denied it.
     */
    public static final class OfURL extends ClasspathEntry {
        /** The URL. */
        private final URL url;

        /**
         * Constructor.
         *
         * @param url
         *            the URL the classpath element was found as.
         * @param location
         *            the location of the classpath element.
         * @param classLoaderName
         *            the string form of the classloader this classpath element was obtained from, or null.
         * @param packageRootPrefixes
         *            the directory prefixes that should be looked for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this element.
         */
        OfURL(final URL url, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.url = url;
        }

        /**
         * Returns the {@link URL} the classloader named this classpath element with.
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
     * A classpath element that a classloader named with a {@link URI} that had no URL scheme this JVM can parse. A
     * {@code "jar:"} or {@code "file:"} URI names something in the local filesystem; with any other scheme,
     * {@link #open(Vfs)} reads it if the JVM has a handler for that scheme and the {@link Vfs} has not denied it.
     */
    public static final class OfURI extends ClasspathEntry {
        /** The URI. */
        private final URI uri;

        /**
         * Constructor.
         *
         * @param uri
         *            the URI the classpath element was found as.
         * @param location
         *            the location of the classpath element.
         * @param classLoaderName
         *            the string form of the classloader this classpath element was obtained from, or null.
         * @param packageRootPrefixes
         *            the directory prefixes that should be looked for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this element.
         */
        OfURI(final URI uri, final String location, final @Nullable String classLoaderName,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            super(location, classLoaderName, packageRootPrefixes, libDirPrefixes);
            this.uri = uri;
        }

        /**
         * Returns the {@link URI} the classloader named this classpath element with.
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
