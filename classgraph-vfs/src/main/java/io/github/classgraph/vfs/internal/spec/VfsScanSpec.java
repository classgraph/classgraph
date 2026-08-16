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
package io.github.classgraph.vfs.internal.spec;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The settings that govern how archives are opened and read. These are the settings that apply to reading the bytes
 * of a resource, as opposed to the settings that govern which classpath elements are found, or how the classfiles
 * found within them are parsed, which are the business of the layers above this one.
 *
 * <p>
 * Every field is volatile, and {@link #enableURLScheme(String)} publishes an immutable set, so that a setting
 * changed by one thread is seen by the threads that read archives, whenever they were started.
 */
public class VfsScanSpec {
    /** The default value of {@link #enableNestedJars}. */
    public static final boolean DEFAULT_ENABLE_NESTED_JARS = true;

    /** The default value of {@link #enableMultiReleaseVersions}. */
    public static final boolean DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS = false;

    /** The default value of {@link #maxBufferedJarRAMSize}, in bytes. */
    public static final int DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE = 64 * 1024 * 1024;

    /** If true, open jarfiles nested within other jarfiles (jarfiles within jarfiles). */
    public volatile boolean enableNestedJars = DEFAULT_ENABLE_NESTED_JARS;

    /** If true, all multi-release versions of a resource are found. */
    public volatile boolean enableMultiReleaseVersions = DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS;

    /**
     * URL schemes that jarfiles may be downloaded from (not counting the optional "jar:" prefix and/or "file:",
     * which are automatically allowed). Only ever assigned an unmodifiable set, so that a reader can iterate it
     * while another thread allows a further scheme.
     */
    public volatile @Nullable Set<String> allowedURLSchemes;

    /**
     * The maximum size of an inner (nested) jar that has been deflated (i.e. compressed, not stored) within an
     * outer jar, before it has to be spilled to disk rather than stored in a RAM-backed {@link ByteBuffer} when it
     * is deflated, in order for the inner jar's entries to be read. (Note that this situation of having to deflate
     * a nested jar to RAM or disk in order to read it is rare, because normally adding a jarfile to another jarfile
     * will store the inner jar, rather than deflate it, because deflating a jarfile does not usually produce any
     * further compression gains. If an inner jar is stored, not deflated, then its zip entries can be read directly
     * using ClassGraph's own zipfile central directory parser, which can use file slicing to extract entries
     * directly from stored nested jars.)
     *
     * <p>
     * This is also the maximum size of a jar downloaded from an {@code http://} or {@code https://} classpath
     * {@link URL} to RAM. Once this many bytes have been read from the {@link URL}'s {@link InputStream}, then the
     * RAM contents are spilled over to a temporary file on disk, and the rest of the content is downloaded to the
     * temporary file. (This is also rare, because normally there are no {@code http://} or {@code https://}
     * classpath entries.)
     *
     * <p>
     * Defaults to {@link #DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE} (i.e. writing to disk is avoided wherever possible).
     * Setting a lower max RAM size value will decrease memory usage if either of the above rare situations occurs.
     */
    public volatile int maxBufferedJarRAMSize = DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;

    /**
     * If true, use a {@link MappedByteBuffer} rather than the {@link FileChannel} API to access file content.
     *
     * <p>
     * Memory mapping is measurably faster on Windows and is not on Linux or macOS, where it can even be slower, so
     * it is turned on for Windows only and there is no API to change it. (The measurements are at
     * <a href="https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark">Memory mapping
     * benchmark</a>.) This field is public so that tests can override the platform's choice and exercise both paths
     * whatever platform they are running on.
     */
    public volatile boolean memoryMapFiles = VersionFinder.OS == OperatingSystem.Windows;

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public VfsScanSpec() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Allow jarfiles to be downloaded from URLs with the given scheme.
     *
     * @param scheme
     *            the scheme, e.g. "http".
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public synchronized void enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
        // Copy on write, rather than adding to the set in place, so that a thread reading the set while this one
        // allows a further scheme sees either the old set or the new one, never a set part-way through an insert
        final var allowedURLSchemesCurr = allowedURLSchemes;
        final Set<String> updated = allowedURLSchemesCurr == null ? new HashSet<>()
                : new HashSet<>(allowedURLSchemesCurr);
        updated.add(normalizedScheme);
        allowedURLSchemes = Collections.unmodifiableSet(updated);
    }

    /**
     * Check whether an entry path names a multi-release versioned section that these settings say to ignore.
     *
     * @param relativePath
     *            the path of an entry, relative to the root it was read from.
     * @return true if the path is within a versioned section, and versioned sections are not being reported.
     */
    public boolean isIgnoredVersionedPath(final String relativePath) {
        return !enableMultiReleaseVersions && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX);
    }

    /**
     * Write to log.
     *
     * @param log
     *            The {@link LogNode} to log to.
     */
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var vfsScanSpecLog = log.log("VfsScanSpec:");
            for (final Field field : VfsScanSpec.class.getDeclaredFields()) {
                // Log the settings, not the constants holding their default values
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    vfsScanSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // Ignore
                }
            }
        }
    }
}
