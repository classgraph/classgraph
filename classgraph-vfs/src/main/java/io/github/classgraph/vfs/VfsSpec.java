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
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;

/**
 * The settings a {@link Vfs} is constructed with: how archives are opened and read.
 *
 * <p>
 * Each setting is changed by a method that returns this same object, so they can be chained onto the constructor
 * call:
 *
 * <pre>
 * try (Vfs vfs = new Vfs(new VfsSpec().disableMultiReleaseVersions().setMaxBufferedJarRAMSize(65536))) {
 *     // ...
 * }
 * </pre>
 *
 * <p>
 * A {@link Vfs} copies the {@link VfsSpec} it is constructed with, so that everything it opens is read with the
 * same settings. A setting changed after that applies only to a {@link Vfs} constructed afterward. The copy, which
 * {@link Vfs#getVfsSpec()} returns, cannot be changed: each method that would change one of its settings throws
 * {@link IllegalStateException}.
 *
 * <p>
 * A setting is safe to change from any thread: each is held in a volatile field, and {@link #denyURLScheme(String)}
 * publishes an unmodifiable set rather than adding to one in place. A {@link Vfs} constructed while another thread
 * is changing settings may copy some of the changes and not others.
 */
public final class VfsSpec {
    /** The default value of {@link #isNestedJarsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_NESTED_JARS = true;

    /** The default value of {@link #isMultiReleaseVersionsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS = true;

    /** The default value of {@link #getMaxBufferedJarRAMSize()}, in bytes. */
    public static final int DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE = 64 * 1024 * 1024;

    /** If true, open jarfiles nested within other jarfiles (jarfiles within jarfiles). */
    private volatile boolean nestedJarsEnabled = DEFAULT_ENABLE_NESTED_JARS;

    /** If true, multi-release versioning is honored, so that an entry is served in the version this JVM runs. */
    private volatile boolean multiReleaseVersionsEnabled = DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS;

    /**
     * URL schemes that a jarfile or directory may not be opened from. Every scheme the JVM has a handler for is
     * allowed unless it appears here. Only ever assigned an unmodifiable set, so that a reader can iterate it while
     * another thread denies a further scheme, and so that a copy can share it.
     */
    private volatile Set<String> deniedURLSchemes = Set.of();

    /** The maximum size of a jarfile that may be held in RAM rather than spilled to disk, in bytes. */
    private volatile int maxBufferedJarRAMSize = DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;

    /**
     * How long to wait for another thread that is opening the same path while it makes no progress, or zero or
     * negative to wait indefinitely.
     */
    private volatile Duration openWaitTimeout = Duration.ZERO;

    /** True if this is the copy that a {@link Vfs} holds, whose settings cannot be changed. */
    private final boolean frozen;

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor, using the default value of every setting. */
    public VfsSpec() {
        this.frozen = false;
    }

    /**
     * Copy constructor, for the copy that a {@link Vfs} holds, whose settings cannot be changed.
     *
     * @param vfsSpec
     *            the settings to copy.
     */
    VfsSpec(final VfsSpec vfsSpec) {
        this.nestedJarsEnabled = vfsSpec.nestedJarsEnabled;
        this.multiReleaseVersionsEnabled = vfsSpec.multiReleaseVersionsEnabled;
        this.deniedURLSchemes = vfsSpec.deniedURLSchemes;
        this.maxBufferedJarRAMSize = vfsSpec.maxBufferedJarRAMSize;
        this.openWaitTimeout = vfsSpec.openWaitTimeout;
        this.frozen = true;
    }

    /**
     * Refuse to change a setting of the copy that a {@link Vfs} holds.
     *
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds.
     */
    private void checkNotFrozen() {
        if (frozen) {
            throw new IllegalStateException("The settings of a Vfs cannot be changed after it is constructed: "
                    + "change the VfsSpec it was constructed with, and construct a new Vfs");
        }
    }

    /**
     * Copy these settings. Unlike the copy that a {@link Vfs} holds, the returned copy can be changed, even when
     * this object is the copy that a {@link Vfs} holds.
     *
     * @return a copy of these settings, which can be changed without changing this object.
     */
    public VfsSpec copy() {
        final var copy = new VfsSpec();
        copy.nestedJarsEnabled = nestedJarsEnabled;
        copy.multiReleaseVersionsEnabled = multiReleaseVersionsEnabled;
        // An unmodifiable set, so it can be shared
        copy.deniedURLSchemes = deniedURLSchemes;
        copy.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        copy.openWaitTimeout = openWaitTimeout;
        return copy;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} can name a jarfile within
     * a jarfile, and not just a package root within a jarfile. This is the default.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec enableNestedJars() {
        checkNotFrozen();
        nestedJarsEnabled = true;
        return this;
    }

    /**
     * Do not open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} only ever names a
     * package root within a jarfile.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec disableNestedJars() {
        checkNotFrozen();
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
     * Honor the multi-release versioning of a jarfile, as the JVM does: serve each entry that
     * {@code "META-INF/versions/<version>/"} holds a versioned copy of in the newest version that is not newer than
     * the running JVM, under the entry's unversioned path. This is the default.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec enableMultiReleaseVersions() {
        checkNotFrozen();
        multiReleaseVersionsEnabled = true;
        return this;
    }

    /**
     * Do not honor the multi-release versioning of a jarfile: report every entry under the path it is stored under,
     * so that the versioned copies of an entry are reported beneath {@code "META-INF/versions/"} rather than
     * masking the entry they are copies of.
     *
     * <p>
     * This shows a caller what a jarfile holds, rather than what the JVM would load out of it. Note that a
     * versioned copy is then only reachable under a path that does not correspond to its package, e.g.
     * {@code "META-INF/versions/11/com/xyz/Widget.class"} for a class in package {@code "com.xyz"}.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec disableMultiReleaseVersions() {
        checkNotFrozen();
        multiReleaseVersionsEnabled = false;
        return this;
    }

    /**
     * Whether the multi-release versioning of a jarfile is honored.
     *
     * @return true if each entry is served in the newest version this JVM can run, or false if every entry is
     *         reported under the path it is stored under.
     */
    public boolean isMultiReleaseVersionsEnabled() {
        return multiReleaseVersionsEnabled;
    }

    /**
     * Refuse to open a jarfile or directory named by a URL with the given scheme. Every scheme is allowed by
     * default, so this is only needed to take one away.
     *
     * <p>
     * A {@link Vfs} opens whatever the JVM can open. A scheme is openable because the JDK ships a
     * {@link java.net.URLStreamHandler} for it ({@code file}, {@code jar}, {@code http}, {@code https},
     * {@code ftp}, {@code mailto}, {@code jrt} and {@code jmod}), or because something registered a handler or a
     * {@link java.nio.file.spi.FileSystemProvider} for it. Denying a scheme is how a caller that reads paths it
     * does not control keeps anything from being read over one -- {@code ClassGraph} denies the four schemes whose
     * handlers connect over a network ({@code http}, {@code https}, {@code ftp} and {@code mailto}, which connects
     * to a mail server even though it cannot fetch a jarfile) for exactly that reason. A path with a denied scheme
     * is refused even if a {@link java.nio.file.spi.FileSystemProvider} installed for the scheme serves a directory
     * there.
     *
     * <p>
     * The scheme is checked whether the path is given as a string, a {@link java.net.URL}, a {@link java.net.URI}
     * or a {@link java.nio.file.Path}. The scheme of a {@link java.nio.file.Path} is the scheme of its URI, so a
     * path in a filesystem whose provider serves the denied scheme is refused. A path in a zipfile mounted as a
     * filesystem has a {@code jar:} URI, so what is checked for it is the scheme of the filesystem that holds the
     * zipfile.
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
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public synchronized VfsSpec denyURLScheme(final String scheme) {
        checkNotFrozen();
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
        // Copy on write, rather than adding to the set in place, so that a thread reading the set while this one
        // denies a further scheme sees either the old set or the new one, never a set part-way through an insert
        final Set<String> updated = new TreeSet<>(deniedURLSchemes);
        updated.add(normalizedScheme);
        deniedURLSchemes = Collections.unmodifiableSet(updated);
        return this;
    }

    /**
     * Allow a jarfile or directory named by a URL with the given scheme to be opened again, undoing a
     * {@link #denyURLScheme(String)}. Every scheme is allowed by default, so this is only needed to take back a
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
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns, even if the
     *             scheme is not denied.
     */
    public synchronized VfsSpec allowURLScheme(final String scheme) {
        checkNotFrozen();
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
        if (!deniedURLSchemes.contains(normalizedScheme)) {
            return this;
        }
        // Copy on write, for the same reason as denyURLScheme()
        final Set<String> updated = new TreeSet<>(deniedURLSchemes);
        updated.remove(normalizedScheme);
        deniedURLSchemes = Collections.unmodifiableSet(updated);
        return this;
    }

    /**
     * The URL schemes that a jarfile or directory may not be opened from. Every other scheme the JVM has a handler
     * for is allowed, as are {@code file:} and {@code jar:}, which denying has no effect on.
     *
     * @return the denied schemes, sorted by scheme name, as an unmodifiable set, which is empty if no scheme has
     *         been denied.
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
     * <p>
     * At most {@code Integer.MAX_VALUE - 8} bytes of a jarfile are held in RAM, since some JVMs cannot allocate a
     * larger array, so a larger value, such as {@link Integer#MAX_VALUE}, has the same effect as
     * {@code Integer.MAX_VALUE - 8}. {@link #getMaxBufferedJarRAMSize()} still returns the value that was set.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes of a jarfile to hold in RAM, before the content is spilled to a
     *            temporary file. This is the limit per jarfile, not for the whole classpath.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec setMaxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        checkNotFrozen();
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

    /**
     * Set how long a thread opening a path waits for another thread that is already opening the same directory or
     * jarfile, while that thread makes no progress. A path is only opened once, however many threads ask for it at
     * the same time, so the threads that ask for it while it is being opened wait for the thread that is opening
     * it. That thread can be stuck, for example reading a jarfile from a server that stops sending it, or from a
     * network filesystem that does not answer, and without a timeout every thread that needs the same jarfile --
     * including a thread that opens a jarfile nested within it, or a package root within it -- would then be stuck
     * too.
     *
     * <p>
     * The timeout counts only time in which no thread reading through the {@link Vfs} made progress, so a thread
     * that is opening a path slowly but steadily is waited for however long it takes. Progress is reported for the
     * bytes read while a jarfile is downloaded from a URL, or while a deflated nested jarfile is read into memory;
     * for the bytes written while either is written to a temporary file; and for each entry listed in a directory.
     * Reading a local jarfile, or a jarfile nested within it without compression, reports no progress, so a read of
     * a local file that blocks forever still times out. The wait is made in slices of one tenth of the timeout, or
     * one second if that is shorter. A slice in which the whole JVM was paused, for example in a debugger, counts
     * as at most one slice, so a pause can only make the wait time out if the other thread had already gone that
     * close to the timeout with no progress.
     *
     * <p>
     * This applies only to the wait for another thread, not to the time a thread takes to open a path itself. When
     * the wait times out, the {@code open} method that was waiting throws {@link java.io.IOException}. The thread
     * that was opening the path is not stopped, and a later {@code open} of the same path gets its root if it
     * finishes.
     *
     * <p>
     * Default: zero, which waits indefinitely, as does any timeout that is negative or too long to express in
     * nanoseconds. {@code ClassGraph} sets this to its worker timeout.
     *
     * @param openWaitTimeout
     *            how long to wait for another thread to open the same path while it makes no progress.
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the copy that a {@link Vfs} holds, which {@link Vfs#getVfsSpec()} returns.
     */
    public VfsSpec setOpenWaitTimeout(final Duration openWaitTimeout) {
        checkNotFrozen();
        Assert.notNull(openWaitTimeout, "openWaitTimeout");
        this.openWaitTimeout = openWaitTimeout;
        return this;
    }

    /**
     * How long a thread opening a path waits for another thread that is already opening the same directory or
     * jarfile, while that thread makes no progress. See {@link #setOpenWaitTimeout(Duration)}.
     *
     * @return the timeout, which waits indefinitely if it is zero or negative.
     */
    public Duration getOpenWaitTimeout() {
        return openWaitTimeout;
    }

    /**
     * Get {@link #getOpenWaitTimeout()} in nanoseconds, for {@link Vfs} to pass to a timed wait.
     *
     * @return the number of nanoseconds to wait, or {@link Long#MAX_VALUE} to wait indefinitely, if the timeout is
     *         zero, negative, or too long to express in nanoseconds.
     */
    long openWaitTimeoutNanos() {
        try {
            final var timeoutNanos = openWaitTimeout.toNanos();
            return timeoutNanos > 0L ? timeoutNanos : Long.MAX_VALUE;
        } catch (final ArithmeticException _) {
            // The timeout is longer than Long.MAX_VALUE nanoseconds (about 292 years)
            return Long.MAX_VALUE;
        }
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
        return multiReleaseVersionsEnabled && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX);
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
                + "; openWaitTimeout: " + openWaitTimeout + ")";
    }
}
