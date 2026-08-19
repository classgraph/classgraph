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
package io.github.classgraph.vfs;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;

/**
 * The settings a {@link Vfs} is constructed with: how archives are opened and read.
 *
 * <p>
 * Each setting is changed by a method that returns this same object, so they can be chained onto the constructor
 * call:
 *
 * <pre>
 * try (Vfs vfs = new Vfs(new VfsSpec().enableMultiReleaseVersions().setMaxBufferedJarRAMSize(65536))) {
 *     // ...
 * }
 * </pre>
 *
 * <p>
 * Every setting is safe to change from any thread, at any time: each is held in a volatile field, and
 * {@link #disableURLScheme(String)} publishes an unmodifiable set rather than adding to one in place, so a setting
 * changed by one thread is seen whole by the threads that read archives, whenever they were started.
 */
public class VfsSpec {
    /** The default value of {@link #isNestedJarsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_NESTED_JARS = true;

    /** The default value of {@link #isMultiReleaseVersionsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS = false;

    /** The default value of {@link #getMaxBufferedJarRAMSize()}, in bytes. */
    public static final int DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE = 64 * 1024 * 1024;

    /** If true, open jarfiles nested within other jarfiles (jarfiles within jarfiles). */
    private volatile boolean nestedJarsEnabled = DEFAULT_ENABLE_NESTED_JARS;

    /** If true, all multi-release versions of a resource are found. */
    private volatile boolean multiReleaseVersionsEnabled = DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS;

    /**
     * URL schemes that jarfiles may not be fetched from. Every scheme the JVM has a handler for is allowed unless
     * it appears here. Only ever assigned an unmodifiable set, so that a reader can iterate it while another thread
     * denies a further scheme.
     */
    private volatile Set<String> deniedURLSchemes = Set.of();

    /** The maximum size of a jarfile that may be held in RAM rather than spilled to disk, in bytes. */
    private volatile int maxBufferedJarRAMSize = DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;

    /**
     * If true, use a {@link MappedByteBuffer} rather than the {@link FileChannel} API to access file content.
     *
     * <p>
     * Memory mapping is measurably faster on Windows and is not on Linux or macOS, where it can even be slower, so
     * it is turned on for Windows only. (The measurements are at
     * <a href="https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark">Memory mapping
     * benchmark</a>.)
     *
     * <p>
     * A file is unmapped when the {@link Vfs} that mapped it is closed, on every JDK version: on JDK 22 and later
     * by closing the {@code java.lang.foreign.Arena} that mapped it, and below that by
     * {@code Unsafe::invokeCleaner}, the only method there is that can unmap a file on demand. That method frees
     * the address range whether or not anything is still reading it, so below JDK 22 a file is left mapped while a
     * {@link CloseableByteBuffer} that the caller has not closed yet is still a view of it, and the last such
     * buffer to be closed unmaps the file instead.
     */
    private volatile boolean memoryMapFiles = VersionFinder.OS == OperatingSystem.Windows;

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor, using the default value of every setting. */
    public VfsSpec() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} can name a jarfile within
     * a jarfile, and not just a package root within a jarfile. This is the default.
     *
     * @return this (for method chaining).
     */
    public VfsSpec enableNestedJars() {
        nestedJarsEnabled = true;
        return this;
    }

    /**
     * Do not open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} only ever names a
     * package root within a jarfile.
     *
     * @return this (for method chaining).
     */
    public VfsSpec disableNestedJars() {
        nestedJarsEnabled = false;
        return this;
    }

    /**
     * Whether jarfiles nested within other jarfiles are opened.
     *
     * @return true if nested jarfiles are opened.
     */
    public boolean isNestedJarsEnabled() {
        return nestedJarsEnabled;
    }

    /**
     * Report every version of a multi-release jarfile's entries, under its {@code META-INF/versions/} path, rather
     * than only the newest version of each entry that this JVM can run.
     *
     * @return this (for method chaining).
     */
    public VfsSpec enableMultiReleaseVersions() {
        multiReleaseVersionsEnabled = true;
        return this;
    }

    /**
     * Report only the newest version of each of a multi-release jarfile's entries that this JVM can run, rather
     * than every version. This is the default.
     *
     * @return this (for method chaining).
     */
    public VfsSpec disableMultiReleaseVersions() {
        multiReleaseVersionsEnabled = false;
        return this;
    }

    /**
     * Whether every version of a multi-release jarfile's entries is reported.
     *
     * @return true if every version is reported, or false if only the newest version this JVM can run is reported.
     */
    public boolean isMultiReleaseVersionsEnabled() {
        return multiReleaseVersionsEnabled;
    }

    /**
     * Refuse to fetch jarfiles from URLs with the given scheme. Every scheme is allowed by default, so this is only
     * needed to take one away.
     *
     * <p>
     * A {@link Vfs} opens whatever the JVM can open. A scheme is openable because the JDK ships a
     * {@link java.net.URLStreamHandler} for it ({@code file}, {@code jar}, {@code http}, {@code https},
     * {@code ftp}, {@code mailto}, {@code jrt} and {@code jmod}), or because something registered a handler or a
     * {@link java.nio.file.spi.FileSystemProvider} for it. Denying a scheme is how a caller that reads paths it
     * does not control keeps a jarfile from being fetched over one -- {@code ClassGraph} denies the four schemes
     * that fetch over a network ({@code http}, {@code https}, {@code ftp} and {@code mailto}) for exactly that
     * reason.
     *
     * <p>
     * Denying {@code file:} or {@code jar:} has no effect. A {@code file:} URL names a local file, and a
     * {@code jar:} URL is only a wrapper around another URL; both prefixes are stripped from a path before it is
     * opened, so what is checked here is the scheme of the URL inside a {@code jar:} URL, if it still has one.
     *
     * @param scheme
     *            the scheme, e.g. {@code "https"}. The scheme name only, without the trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public synchronized VfsSpec disableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
        // Copy on write, rather than adding to the set in place, so that a thread reading the set while this one
        // denies a further scheme sees either the old set or the new one, never a set part-way through an insert
        final Set<String> updated = new HashSet<>(deniedURLSchemes);
        updated.add(normalizedScheme);
        deniedURLSchemes = Collections.unmodifiableSet(updated);
        return this;
    }

    /**
     * Allow jarfiles to be fetched from URLs with the given scheme again, undoing a
     * {@link #disableURLScheme(String)}. Every scheme is allowed by default, so this is only needed to take back a
     * scheme that was denied.
     *
     * <p>
     * A jarfile fetched from a URL is downloaded in full before its entries can be read, since a zipfile's central
     * directory is at the end of the file. A URL whose scheme has a {@link java.nio.file.spi.FileSystemProvider}
     * installed for it is read in place through that filesystem instead, without being copied.
     *
     * @param scheme
     *            the scheme, e.g. {@code "https"}. The scheme name only, without the trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public synchronized VfsSpec enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
        if (!deniedURLSchemes.contains(normalizedScheme)) {
            return this;
        }
        // Copy on write, for the same reason as disableURLScheme()
        final Set<String> updated = new HashSet<>(deniedURLSchemes);
        updated.remove(normalizedScheme);
        deniedURLSchemes = Collections.unmodifiableSet(updated);
        return this;
    }

    /**
     * The URL schemes that jarfiles may not be fetched from. Every other scheme the JVM has a handler for is
     * allowed, as are {@code file:} and {@code jar:}, which denying has no effect on.
     *
     * @return the denied schemes, as an unmodifiable set, which is empty if no scheme has been denied.
     */
    public Set<String> getDeniedURLSchemes() {
        return deniedURLSchemes;
    }

    /**
     * Set the number of bytes of a jarfile that may be held in RAM before it is spilled to a temporary file on
     * disk.
     *
     * <p>
     * This only applies to jarfiles that cannot be read in place: a nested jarfile that is stored deflated (i.e.
     * compressed) rather than uncompressed within its enclosing jarfile, a jarfile downloaded from a URL, and a
     * jarfile read from an {@link InputStream}. All three are rare: adding a jarfile to another jarfile normally
     * stores the inner jar rather than deflating it, since deflating a jarfile does not usually produce any further
     * compression gains, and a stored nested jar is read in place with file slicing; and there are not normally any
     * {@code http://} or {@code https://} classpath entries.
     *
     * <p>
     * Defaults to {@value #DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE} bytes (64MB), i.e. writing to disk is avoided
     * wherever possible. Setting a lower value decreases memory usage if any of the above situations does occur.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes to hold in a RAM-backed {@link ByteBuffer} per jarfile, before the
     *            content is spilled to a temporary file. This is the limit per jarfile, not for the whole
     *            classpath.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     */
    public VfsSpec setMaxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        if (maxBufferedJarRAMSize < 0) {
            throw new IllegalArgumentException("maxBufferedJarRAMSize cannot be negative");
        }
        this.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return this;
    }

    /**
     * The number of bytes of a jarfile that may be held in RAM before it is spilled to a temporary file on disk.
     *
     * @return the maximum number of bytes to hold in RAM per jarfile.
     */
    public int getMaxBufferedJarRAMSize() {
        return maxBufferedJarRAMSize;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check whether an entry path names a multi-release versioned section that these settings say to ignore. This
     * is for the other ClassGraph modules, which filter the entries they read, and is not part of the API.
     *
     * @param relativePath
     *            the path of an entry, relative to the root it was read from.
     * @return true if the path is within a versioned section, and versioned sections are not being reported.
     * @hidden
     */
    public boolean isIgnoredVersionedPath(final String relativePath) {
        return !multiReleaseVersionsEnabled && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX);
    }

    /**
     * Whether file content is read through a {@link MappedByteBuffer} rather than through the {@link FileChannel}
     * API. This follows the platform -- memory mapping is only faster on Windows -- so it is not part of the API.
     *
     * @return true if file content is memory mapped.
     * @hidden
     */
    public boolean isMemoryMappingFiles() {
        return memoryMapFiles;
    }

    /**
     * Override the platform's choice of whether to read file content through a {@link MappedByteBuffer} rather than
     * through the {@link FileChannel} API. This exists so that a test can exercise both paths whatever platform it
     * is running on, and is not part of the API.
     *
     * @param memoryMapFiles
     *            true to memory map file content.
     * @return this (for method chaining).
     * @hidden
     */
    public VfsSpec setMemoryMappingFiles(final boolean memoryMapFiles) {
        this.memoryMapFiles = memoryMapFiles;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns a description of these settings, for the verbose log. This is meant to be read by a person, and is
     * not a stable format to parse.
     *
     * @return a description of the settings.
     */
    @Override
    public String toString() {
        return "VfsSpec(nestedJars: " + nestedJarsEnabled //
                + "; multiReleaseVersions: " + multiReleaseVersionsEnabled //
                + "; deniedURLSchemes: " + deniedURLSchemes //
                + "; maxBufferedJarRAMSize: " + maxBufferedJarRAMSize //
                + "; memoryMapFiles: " + memoryMapFiles + ")";
    }
}
