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

import org.jetbrains.annotations.Unmodifiable;

import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;

/**
 * Configuration settings for a {@link Vfs}.
 *
 * <p>
 * Settings can be chained when constructing a {@link Vfs}:
 *
 * <pre>
 * try (Vfs vfs = new Vfs(new VfsSpec().disableMultiReleaseVersions().setMaxBufferedJarRAMSize(65536))) {
 *     // ...
 * }
 * </pre>
 *
 * <p>
 * A {@link Vfs} stores an immutable copy of the {@link VfsSpec} it was constructed with (returned by
 * {@link Vfs#getVfsSpec()}). Modifying a {@link VfsSpec} after constructing a {@link Vfs} affects only subsequently
 * constructed {@link Vfs} instances, and calling a mutator on the frozen copy returned by {@link Vfs#getVfsSpec()}
 * throws {@link IllegalStateException}.
 */
public final class VfsSpec {
    /** The default value of {@link #isNestedJarsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_NESTED_JARS = true;

    /** The default value of {@link #isMultiReleaseVersionsEnabled()}. */
    public static final boolean DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS = true;

    /** The default value of {@link #getMaxBufferedJarRAMSize()}, in bytes. */
    public static final int DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE = 64 * 1024 * 1024;

    /** If true, open jarfiles nested within other jarfiles. */
    private volatile boolean nestedJarsEnabled = DEFAULT_ENABLE_NESTED_JARS;

    /** If true, honor multi-release jarfile versioning for the running JVM. */
    private volatile boolean multiReleaseVersionsEnabled = DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS;

    /** Unmodifiable set of disallowed URL schemes. */
    private volatile Set<String> deniedURLSchemes = Set.of();

    /** Maximum jarfile size to hold in RAM before spilling to a temporary file, in bytes. */
    private volatile int maxBufferedJarRAMSize = DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;

    /**
     * Timeout for waiting on another thread that is opening the same path without making progress, or zero or
     * negative to wait indefinitely.
     */
    private volatile Duration openWaitTimeout = Duration.ZERO;

    /** True if this instance is an immutable copy held by a {@link Vfs}. */
    private final boolean frozen;

    // -------------------------------------------------------------------------------------------------------------

    /** Creates a {@link VfsSpec} with default settings. */
    public VfsSpec() {
        this.frozen = false;
    }

    /**
     * Copy constructor that creates an immutable copy for a {@link Vfs}.
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
     * Throws {@link IllegalStateException} if this instance is frozen.
     *
     * @throws IllegalStateException
     *             if this instance is frozen.
     */
    private void checkNotFrozen() {
        if (frozen) {
            throw new IllegalStateException("The settings of a Vfs cannot be changed after it is constructed: "
                    + "change the VfsSpec it was constructed with, and construct a new Vfs");
        }
    }

    /**
     * Returns a mutable copy of these settings.
     *
     * @return a mutable copy of these settings.
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
     * Enable opening jarfiles nested within other jarfiles (the default).
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
     */
    public VfsSpec enableNestedJars() {
        checkNotFrozen();
        nestedJarsEnabled = true;
        return this;
    }

    /**
     * Disable opening jarfiles nested within other jarfiles, so that {@code "!/"} in a path only names a package
     * root directory within a jarfile.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
     */
    public VfsSpec disableNestedJars() {
        checkNotFrozen();
        nestedJarsEnabled = false;
        return this;
    }

    /**
     * Returns whether jarfiles nested within other jarfiles are opened.
     *
     * @return true if nested jarfiles are opened.
     */
    public boolean isNestedJarsEnabled() {
        return nestedJarsEnabled;
    }

    /**
     * Enable multi-release jarfile versioning (the default), serving the newest version compatible with the running
     * JVM from {@code "META-INF/versions/<version>/"} under each entry's unversioned path.
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
     */
    public VfsSpec enableMultiReleaseVersions() {
        checkNotFrozen();
        multiReleaseVersionsEnabled = true;
        return this;
    }

    /**
     * Disable multi-release jarfile versioning, reporting every entry under its raw path in the archive (including
     * entries beneath {@code "META-INF/versions/"}).
     *
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
     */
    public VfsSpec disableMultiReleaseVersions() {
        checkNotFrozen();
        multiReleaseVersionsEnabled = false;
        return this;
    }

    /**
     * Returns whether multi-release jarfile versioning is enabled.
     *
     * @return true if multi-release versioning is enabled.
     */
    public boolean isMultiReleaseVersionsEnabled() {
        return multiReleaseVersionsEnabled;
    }

    /**
     * Disallow opening jarfiles or directories from URLs with the given scheme. All schemes are allowed by default.
     *
     * <p>
     * Applies to string paths, {@link java.net.URL}s, {@link java.net.URI}s, and {@link java.nio.file.Path}s
     * (checked via the scheme of the path's URI, or the underlying filesystem's scheme for a {@code jar:} URI).
     * Denying {@code file} or {@code jar} has no effect; for {@code jar:} URLs, the scheme of the wrapped URL is
     * checked.
     *
     * @param scheme
     *            the URL scheme (e.g. {@code "https"}), without a trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
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
     * Allow opening jarfiles or directories from URLs with the given scheme, reversing a previous call to
     * {@link #denyURLScheme(String)}. All schemes are allowed by default.
     *
     * @param scheme
     *            the URL scheme (e.g. {@code "https"}), without a trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
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
     * Returns the disallowed URL schemes.
     *
     * @return the denied schemes, sorted by name, as an unmodifiable set.
     */
    public @Unmodifiable Set<String> getDeniedURLSchemes() {
        return deniedURLSchemes;
    }

    /**
     * Set the maximum size of a jarfile (in bytes) that may be buffered in RAM before spilling to a temporary file
     * on disk.
     *
     * <p>
     * Applies to jarfiles that cannot be read in place: deflated nested jarfiles, jarfiles downloaded from URLs,
     * and jarfiles read from an {@link InputStream}. Defaults to {@value #DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE} bytes
     * (64 MiB), capped internally at {@code Integer.MAX_VALUE - 8} bytes.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes per jarfile to hold in RAM before spilling to a temporary file.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
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
     * Returns the maximum size of a jarfile (in bytes) that may be buffered in RAM before spilling to a temporary
     * file on disk.
     *
     * @return the maximum number of bytes to hold in RAM per jarfile.
     */
    public int getMaxBufferedJarRAMSize() {
        return maxBufferedJarRAMSize;
    }

    /**
     * Set how long a thread opening a path waits for another thread that is already opening the same directory or
     * jarfile while no progress is made across the {@link Vfs}.
     *
     * <p>
     * Applies only when waiting for another thread; if the wait times out, the waiting {@code open} call throws
     * {@link java.io.IOException} without interrupting the thread performing the open. Defaults to
     * {@link Duration#ZERO}, which (like any negative or overflowing duration) waits indefinitely.
     *
     * @param openWaitTimeout
     *            how long to wait for another thread to open the same path without progress.
     * @return this (for method chaining).
     * @throws IllegalStateException
     *             if this is the immutable copy returned by {@link Vfs#getVfsSpec()}.
     */
    public VfsSpec setOpenWaitTimeout(final Duration openWaitTimeout) {
        checkNotFrozen();
        Assert.notNull(openWaitTimeout, "openWaitTimeout");
        this.openWaitTimeout = openWaitTimeout;
        return this;
    }

    /**
     * Returns how long a thread opening a path waits for another thread that is already opening the same path
     * without progress. See {@link #setOpenWaitTimeout(Duration)}.
     *
     * @return the timeout (zero or negative waits indefinitely).
     */
    public Duration getOpenWaitTimeout() {
        return openWaitTimeout;
    }

    /**
     * Returns {@link #getOpenWaitTimeout()} in nanoseconds, or {@link Long#MAX_VALUE} if zero, negative, or
     * overflowing.
     *
     * @return the timeout in nanoseconds, or {@link Long#MAX_VALUE} to wait indefinitely.
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
     * Returns whether an entry path lies within {@code "META-INF/versions/"} when multi-release versioning is
     * enabled.
     *
     * @param relativePath
     *            the path of an entry relative to its root.
     * @return true if the path is within a versioned section that should be ignored.
     * @hidden
     */
    public boolean isIgnoredVersionedPath(final String relativePath) {
        return multiReleaseVersionsEnabled && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns a string representation of these settings.
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
