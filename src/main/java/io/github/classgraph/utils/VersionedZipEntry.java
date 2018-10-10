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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.util.zip.ZipEntry;

/**
 * A {@link ZipEntry} with a multi-release jar version number, and the {@link ZipEntry} path with the version path
 * prefix and/or any Spring-Boot path prefix stripped.
 */
public class VersionedZipEntry implements Comparable<VersionedZipEntry> {
    /** The {@link ZipEntry}. */
    public final ZipEntry zipEntry;

    /**
     * The version code (&gt;= 9), or 8 for the base layer or a non-versioned jar (whether JDK 7 or 8 compatible).
     */
    public final int version;

    /**
     * The ZipEntry path with any "META-INF/versions/{version}" version prefix (and/or Spring-Boot
     * "BOOT-INF/classes/" prefix) stripped.
     */
    public final String unversionedPath;

    /**
     * A versioned {@link ZipEntry}.
     * 
     * @param zipEntry
     *            The {@link ZipEntry}.
     * @param version
     *            The multi-release jar version number (for a {@link ZipEntry} path of
     *            "META-INF/versions/{version}", or 8 for the base layer.
     * @param unversionedPath
     *            The unversioned path (i.e. {@link ZipEntry#getName()} with the version path prefix stripped).
     */
    public VersionedZipEntry(final ZipEntry zipEntry, final int version, final String unversionedPath) {
        this.zipEntry = zipEntry;
        this.version = version;
        this.unversionedPath = unversionedPath;
    }

    /** Sort in decreasing order of version number, then in increasing lexicographic order of path */
    @Override
    public int compareTo(final VersionedZipEntry o) {
        final int diff = o.version - this.version;
        if (diff != 0) {
            return diff;
        }
        return unversionedPath.compareTo(o.unversionedPath);
    }
}