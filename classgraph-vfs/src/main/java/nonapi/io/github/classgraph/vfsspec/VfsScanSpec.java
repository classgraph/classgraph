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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.vfsspec;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;
import org.jspecify.annotations.Nullable;

/**
 * The settings that govern how archives are opened and read. These are the settings that apply to reading the bytes
 * of a resource, as opposed to the settings that govern which classpath elements are found, or how the classfiles
 * found within them are parsed, which are the business of the layers above this one.
 */
public class VfsScanSpec {
    /** If true, open jarfiles nested within other jarfiles (jarfiles within jarfiles). */
    public boolean scanNestedJars = true;

    /** If true, all multi-release versions of a resource are found. */
    public boolean enableMultiReleaseVersions;

    /**
     * URL schemes that jarfiles may be downloaded from (not counting the optional "jar:" prefix and/or "file:",
     * which are automatically allowed).
     */
    public @Nullable Set<String> allowedURLSchemes;

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
     * Default: 64MB (i.e. writing to disk is avoided wherever possible). Setting a lower max RAM size value will
     * decrease memory usage if either of the above rare situations occurs.
     */
    public int maxBufferedJarRAMSize = 64 * 1024 * 1024;

    /**
     * If true, use a {@link MappedByteBuffer} rather than the {@link FileChannel} API to access file content.
     *
     * <p>
     * Memory mapping is measurably faster on Windows and is not on Linux or macOS, where it can even be slower, so
     * it is turned on for Windows only and there is no API to change it (see BENCHMARK.md for the measurements).
     * This field is public so that tests can override the platform's choice and exercise both paths whatever
     * platform they are running on.
     */
    public boolean memoryMapFiles = VersionFinder.OS == OperatingSystem.Windows;

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
     *             if the scheme is shorter than two characters, since a one-character scheme cannot be told apart
     *             from a Windows drive letter.
     */
    public void enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        if (scheme.length() < 2) {
            throw new IllegalArgumentException("URL schemes must contain at least two characters");
        }
        if (allowedURLSchemes == null) {
            allowedURLSchemes = new HashSet<>();
        }
        allowedURLSchemes.add(scheme.toLowerCase(Locale.ROOT));
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
                try {
                    vfsScanSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // Ignore
                }
            }
        }
    }
}
