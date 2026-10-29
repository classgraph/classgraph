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

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The names that the directories under a base directory list, kept so that whether a name found by the filesystem
 * is spelled exactly as it is stored can be checked without reading the filesystem again. On a filesystem that
 * folds case, such as those of Windows and macOS, a lookup also finds a file under a name whose case differs from
 * the name it is stored under, and the listing of its directory is what tells such a match from an exact one: a
 * file, or a symbolic link, is listed under the name it is stored under, and a folded name is not listed at all.
 * Reading the real path of each file instead costs several system calls per lookup, and on Windows costs more than
 * reading the file.
 *
 * <p>
 * A directory is listed the first time a name is looked up in it, and again whenever a name that the filesystem
 * found is missing from its listing, so that a file created since the directory was listed is found. A file whose
 * name is changed only in case after its directory was listed is still found under its old name, until a name that
 * is not in the listing causes the directory to be listed again.
 */
final class ExactNameListings {
    /** The base directory. */
    private final Path dir;

    /**
     * The names that each directory lists, by the directory's path relative to {@link #dir}, with {@code '/'} as
     * the separator, or {@code ""} for {@link #dir} itself.
     */
    private final Map<String, Set<String>> listings = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param dir
     *            the base directory.
     */
    ExactNameListings(final Path dir) {
        this.dir = dir;
    }

    /**
     * Returns whether a name that the filesystem found under the base directory differs in case from the name that
     * a file or directory along its path is stored under.
     *
     * @param name
     *            the path relative to the base directory, in canonical form (see
     *            {@link VfsRoot#isCanonicalEntryName}), which the filesystem found.
     * @return true if some segment of the name is not listed in its directory, false if every segment is listed
     *         exactly as it is spelled in the name, or null if some directory along the path could not be listed.
     */
    @Nullable
    Boolean isCaseFolded(final String name) {
        var segmentStart = 0;
        while (segmentStart < name.length()) {
            final var slashIdx = name.indexOf('/', segmentStart);
            final var segmentEnd = slashIdx < 0 ? name.length() : slashIdx;
            final var parent = segmentStart == 0 ? "" : name.substring(0, segmentStart - 1);
            final var segment = name.substring(segmentStart, segmentEnd);
            var listing = listings.get(parent);
            if (listing == null || !listing.contains(segment)) {
                // List the directory, or list it again, in case the file was created after it was last listed
                listing = list(parent);
                if (listing == null) {
                    return null;
                }
                if (!listing.contains(segment)) {
                    return Boolean.TRUE;
                }
            }
            segmentStart = segmentEnd + 1;
        }
        return Boolean.FALSE;
    }

    /**
     * List a directory, and keep the listing.
     *
     * @param relativeDir
     *            the path of the directory relative to the base directory, or {@code ""} for the base directory.
     * @return the names the directory lists, or null if it could not be listed.
     */
    private @Nullable Set<String> list(final String relativeDir) {
        final var names = new HashSet<String>();
        try (var listing = Files.newDirectoryStream(relativeDir.isEmpty() ? dir : dir.resolve(relativeDir))) {
            for (final var file : listing) {
                names.add(file.getFileName().toString());
            }
        } catch (final IOException | DirectoryIteratorException | SecurityException _) {
            // A directory that can be searched but not read
            return null;
        }
        final var namesSet = Set.copyOf(names);
        listings.put(relativeDir, namesSet);
        return namesSet;
    }
}
