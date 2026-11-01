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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.path.FileUtils;

/**
 * Lists a directory together with the attributes of each file and subdirectory in it.
 *
 * <p>
 * The directory is listed through {@link Files#walkFileTree} with a depth of 1, rather than through
 * {@link Files#newDirectoryStream} followed by a {@link Files#readAttributes} call per child, because on Windows
 * the listing already holds the attributes of each child, and only the tree walker reuses them. A stat costs about
 * 13µs on Windows, so this removes most of the cost of listing a directory of classfiles there. On Linux and macOS
 * the listing holds no attributes, so the walker reads them with one metadata read per child, the same number as
 * before.
 */
final class DirListing {
    /**
     * A file or subdirectory of a listed directory.
     *
     * @param path
     *            the path of the child.
     * @param attributes
     *            its attributes, read with symbolic links followed.
     * @param mayBeLink
     *            false if the listing showed that the child is not a symbolic link, junction or other reparse
     *            point, otherwise true.
     */
    record Child(Path path, BasicFileAttributes attributes, boolean mayBeLink) {
    }

    /**
     * A listed directory.
     *
     * @param attributes
     *            the attributes of the directory itself, or null if it is a symbolic link or junction, which the
     *            listing read only without following it.
     * @param children
     *            the children, in the order the filesystem listed them.
     */
    record Listing(@Nullable BasicFileAttributes attributes, List<Child> children) {
    }

    /** Constructor. */
    private DirListing() {
        // Not instantiable
    }

    /**
     * List the children of a directory.
     *
     * @param dir
     *            the directory.
     * @param onEachChild
     *            run once for each child listed, or null.
     * @return the listing.
     * @throws IOException
     *             if the directory could not be opened or read.
     */
    static Listing list(final Path dir, final @Nullable Runnable onEachChild) throws IOException {
        final List<Child> children = new ArrayList<>();
        final var dirIsLink = new boolean[1];
        final var dirAttributes = new AtomicReference<@Nullable BasicFileAttributes>();
        Files.walkFileTree(dir, Set.of(), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path d, final BasicFileAttributes attributes) {
                // Called only for the directory itself, since the depth is 1
                dirAttributes.set(attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                if (file.equals(dir)) {
                    // The directory itself, if it is a symbolic link or junction, which the walker does not follow
                    dirIsLink[0] = true;
                    return FileVisitResult.CONTINUE;
                }
                children.add(child(file, attributes));
                if (onEachChild != null) {
                    onEachChild.run();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException e) throws IOException {
                if (file.equals(dir)) {
                    throw e;
                }
                // The attributes of a child could not be read, for example because it was deleted after it was
                // listed -- fall back the same way as FileUtils#readAttributes does
                children.add(new Child(file, FileUtils.readAttributes(file), true));
                if (onEachChild != null) {
                    onEachChild.run();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path d, final IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (dirIsLink[0]) {
            // List the directory the link points to, keeping the paths of the children under the link. The listing
            // holds no attributes, so read them without following links, as the walker does, which tells the
            // children that are links apart at no extra cost.
            try (var dirStream = Files.newDirectoryStream(dir)) {
                for (final Path child : dirStream) {
                    Child listed;
                    try {
                        listed = child(child,
                                Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
                    } catch (final IOException _) {
                        // Fall back the same way as visitFileFailed does
                        listed = new Child(child, FileUtils.readAttributes(child), true);
                    }
                    children.add(listed);
                    if (onEachChild != null) {
                        onEachChild.run();
                    }
                }
            }
        }
        return new Listing(dirAttributes.get(), children);
    }

    /**
     * Make a child from attributes read without following links.
     *
     * @param path
     *            the path of the child.
     * @param attributes
     *            its attributes, read without following links.
     * @return the child.
     */
    private static Child child(final Path path, final BasicFileAttributes attributes) {
        // The attributes of a link are those of the link itself -- read them again, following the link, as
        // Files#readAttributes does by default
        final var isLink = attributes.isSymbolicLink() || attributes.isOther();
        return new Child(path, isLink ? FileUtils.readAttributes(path) : attributes, isLink);
    }
}
