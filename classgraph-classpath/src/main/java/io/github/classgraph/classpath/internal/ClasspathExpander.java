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
package io.github.classgraph.classpath.internal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;
import org.jspecify.annotations.Nullable;

/**
 * Finds the classpath entries that a classpath element adds to the classpath: the jarfiles in its automatic lib
 * dirs, and the entries of its manifest's {@code Class-Path} and {@code Bundle-ClassPath} attributes.
 *
 * <p>
 * A classpath element is read through the virtual filesystem, so a jarfile, a jarfile nested inside another jarfile
 * and an exploded jarfile in a directory all declare their child classpath elements the same way. The one
 * difference between them is how a path within the element is written: a jarfile separates itself from a path
 * within it with {@code "!/"}, where a directory simply has the path below it.
 *
 * <p>
 * A classpath is not complete until these have been resolved, since each of them can in turn declare more of them.
 * The caller drives the recursion, because the two callers need different things from it: the classpath finder
 * wants the expanded list of classpath entries, and the scanner wants a classpath element object for each one.
 *
 * <p>
 * The returned entries are in the order they must be added to the classpath, so a caller that schedules them for
 * parallel processing must record that order, and must not rely on the order in which the work finishes.
 */
public final class ClasspathExpander {
    /** The manifest attribute that lists the classpath elements a jarfile depends on. */
    private static final String CLASS_PATH_KEY = "Class-Path";

    /** The manifest attribute that an OSGi bundle lists the classpath elements within itself with. */
    private static final String BUNDLE_CLASS_PATH_KEY = "Bundle-ClassPath";

    /** Cannot be constructed. */
    private ClasspathExpander() {
        // Cannot be constructed
    }

    /** Why a classpath element declared a child classpath entry. */
    public enum ChildEntryOrigin {
        /**
         * A jarfile in one of the lib dirs that are automatically added to the classpath, e.g. {@code "lib/"}. Not
         * all classloaders list these as classpath elements, so they are found by looking for them.
         */
        NESTED_LIB_JAR("Found nested lib jar"),

        /** An entry of the manifest's {@code Class-Path} attribute. */
        CLASS_PATH_MANIFEST_ENTRY("Found Class-Path manifest entry"),

        /** An entry of the manifest's {@code Bundle-ClassPath} attribute. */
        BUNDLE_CLASS_PATH_MANIFEST_ENTRY("Found Bundle-ClassPath manifest entry");

        /** The text to log when an entry with this origin is found. */
        private final String logMessage;

        /**
         * Constructor.
         *
         * @param logMessage
         *            the text to log when an entry with this origin is found
         */
        ChildEntryOrigin(final String logMessage) {
            this.logMessage = logMessage;
        }

        /**
         * Get the text to log when an entry with this origin is found.
         *
         * @return the log message.
         */
        public String getLogMessage() {
            return logMessage;
        }
    }

    /**
     * A classpath entry declared by a classpath element.
     *
     * @param origin
     *            why the classpath element declared it.
     * @param location
     *            the location of the child classpath entry, resolved so that it can be opened directly.
     * @param path
     *            the child classpath entry as a {@link Path} in the same filesystem as the classpath element that
     *            declared it, or null if it does not have one -- a jarfile nested inside another jarfile is read
     *            out of the jarfile that contains it rather than from a file of its own. This is the only way to
     *            reach a classpath element in a filesystem other than the default one, since its
     *            {@link #location()} names it but cannot open it.
     */
    public record ChildEntry(ChildEntryOrigin origin, String location, @Nullable Path path) {
    }

    /**
     * Find the classpath entries that a classpath element adds to the classpath.
     *
     * @param root
     *            the classpath element, opened through the virtual filesystem.
     * @param libDirPrefixes
     *            the lib dirs to look in for jarfiles that the classloader loads without listing them as classpath
     *            elements, each ending in a slash.
     * @param enableNestedJars
     *            whether jarfiles nested inside other jarfiles are read. A lib dir of an exploded jarfile in a
     *            directory holds jarfiles of its own, which are read whether or not this is set, since they are
     *            files in the filesystem rather than jarfiles nested inside another jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the child classpath entries, in the order they must be added to the classpath.
     * @throws IOException
     *             if the classpath element could not be read.
     */
    public static List<ChildEntry> childEntries(final VfsRoot root, final List<String> libDirPrefixes,
            final boolean enableNestedJars, final @Nullable LogNode log) throws IOException {
        // The lib dirs and the manifest of a jarfile lie outside its package root, so they are looked for in the
        // whole of the classpath element rather than in the part of it that the classes are loaded from
        final var container = root.getContainerRoot();
        final List<ChildEntry> childEntries = new ArrayList<>();
        // The classpath entries a classpath element declares for itself come before the jarfiles that were merely
        // found in one of its lib dirs, since an earlier classpath element masks a later one, and a classloader that
        // serves an archive with both loads a class from the archive's own classes in preference to a copy of it in
        // a bundled dependency -- Tomcat looks in "WEB-INF/classes/" before "WEB-INF/lib/", and Spring Boot's
        // launcher looks in "BOOT-INF/classes/" before "BOOT-INF/lib/". (The package root itself is not a child
        // classpath entry, and is added by the caller, ahead of all of these.)
        addClassPathManifestEntries(container, childEntries, log);
        addBundleClassPathManifestEntries(container, childEntries);
        if (enableNestedJars || container.getKind() != VfsRoot.Kind.ARCHIVE) {
            addLibJars(container, libDirPrefixes, childEntries);
        }
        return childEntries;
    }

    /**
     * Add the jarfiles in the automatic lib dirs, since not all classloaders list them as classpath elements.
     *
     * @param container
     *            the classpath element, opened through the virtual filesystem.
     * @param libDirPrefixes
     *            the lib dirs to look in, each ending in a slash.
     * @param childEntries
     *            the list to add the child classpath entries to.
     * @throws IOException
     *             if the classpath element could not be listed.
     */
    private static void addLibJars(final VfsRoot container, final List<String> libDirPrefixes,
            final List<ChildEntry> childEntries) throws IOException {
        // A jarfile can lie in more than one of the lib dirs only if one of them is below another, but list the
        // names that have been added anyway, since adding the same jarfile to the classpath twice would make it mask
        // itself
        final Set<String> namesAdded = new HashSet<>();
        for (final String libDirPrefix : libDirPrefixes) {
            // Each lib dir is listed on its own, so that the order of libDirPrefixes decides which lib dir's jars
            // come first, and the entries of each come back in the order the virtual filesystem reports them in,
            // which is sorted by name for a directory and central directory order for a jarfile
            for (final VfsEntry entry : container.getEntries(libDirPrefix)) {
                final var name = entry.getPathFromRoot();
                if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && namesAdded.add(name)) {
                    childEntries.add(
                            new ChildEntry(ChildEntryOrigin.NESTED_LIB_JAR, entry.getPath(), entry.getNioPath()));
                }
            }
        }
    }

    /**
     * Add the entries of the manifest's {@code Class-Path} attribute, resolving each path against the directory or
     * jarfile that contains the classpath element.
     *
     * @param container
     *            the classpath element, opened through the virtual filesystem.
     * @param childEntries
     *            the list to add the child classpath entries to.
     * @param log
     *            the log node, or null to skip logging.
     * @throws IOException
     *             if the manifest could not be read.
     */
    private static void addClassPathManifestEntries(final VfsRoot container, final List<ChildEntry> childEntries,
            final @Nullable LogNode log) throws IOException {
        final var classPath = container.getManifestEntry(CLASS_PATH_KEY);
        if (classPath == null) {
            return;
        }
        if (log != null) {
            log.log("Found Class-Path entry in manifest file: " + classPath);
        }
        // A Class-Path entry is resolved against the directory that contains the classpath element, which for a
        // jarfile nested inside another jarfile is a directory within the jarfile that contains it. Resolving it
        // there rather than against the whole path is what keeps a ".." from climbing out of that jarfile.
        final var containerPath = container.getPath();
        final var separatorIdx = containerPath.lastIndexOf("!/");
        final var outerPath = separatorIdx < 0 ? null : containerPath.substring(0, separatorIdx);
        final var parentDir = PathSyntax
                .getParentDirPath(separatorIdx < 0 ? containerPath : containerPath.substring(separatorIdx + 2));
        final var containerNioPath = pathOfRoot(container);
        for (final String relativePath : classPath.split(" ")) {
            if (relativePath.isEmpty()) {
                continue;
            }
            final var resolved = FastPathResolver.resolve(parentDir, relativePath);
            // If the classpath element is nested inside a jarfile, so is anything beside it
            final var location = outerPath == null ? resolved
                    : outerPath + (resolved.startsWith("/") ? "!" : "!/") + resolved;
            childEntries.add(new ChildEntry(ChildEntryOrigin.CLASS_PATH_MANIFEST_ENTRY, location,
                    outerPath != null ? null : siblingPath(containerNioPath, parentDir, location)));
        }
    }

    /**
     * Add the entries of an OSGi bundle jar manifest's {@code Bundle-ClassPath} attribute, resolving each path
     * against the root of the classpath element.
     *
     * @param container
     *            the classpath element, opened through the virtual filesystem.
     * @param childEntries
     *            the list to add the child classpath entries to.
     * @throws IOException
     *             if the manifest could not be read.
     */
    private static void addBundleClassPathManifestEntries(final VfsRoot container,
            final List<ChildEntry> childEntries) throws IOException {
        final var bundleClassPath = container.getManifestEntry(BUNDLE_CLASS_PATH_KEY);
        if (bundleClassPath == null) {
            return;
        }
        // A path within a jarfile is reached through the nested jar separator, where a path within an exploded
        // jarfile is simply a path below the directory
        final var isArchive = container.getKind() == VfsRoot.Kind.ARCHIVE;
        final var locationPrefix = container.getPath() + (isArchive ? "!/" : "/");
        final var containerNioPath = isArchive ? null : pathOfRoot(container);
        // Class-Path is split on " ", but Bundle-ClassPath is split on ","
        for (String relativePath : bundleClassPath.split(",")) {
            // A Bundle-ClassPath entry has to be given relative to the root of the jarfile
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            // Where "." falls among the other entries is ignored, and it is treated as if it came first, since a
            // child classpath entry is always added to the classpath after the classpath element that declared it
            if (relativePath.isEmpty() || ".".equals(relativePath)) {
                continue;
            }
            final var nameWithin = PathSyntax.sanitizeEntryPath(relativePath, /* removeInitialSlash = */ true,
                    /* removeFinalSlash = */ true);
            childEntries.add(new ChildEntry(ChildEntryOrigin.BUNDLE_CLASS_PATH_MANIFEST_ENTRY,
                    locationPrefix + nameWithin, resolveWithin(containerNioPath, nameWithin)));
        }
    }

    /**
     * The path a classpath element is stored at in its own filesystem, or null if it is not stored at one: a
     * jarfile nested inside another jarfile is read out of the jarfile that contains it, and a jarfile read from a
     * stream or downloaded from a URL is not stored at a path at all.
     *
     * @param root
     *            the classpath element, opened through the virtual filesystem.
     * @return the path of the classpath element, or null if it does not have one.
     */
    private static @Nullable Path pathOfRoot(final VfsRoot root) {
        final var nioPath = root.getNioPath();
        // A root reports the path of the file it is physically stored in, which is the classpath element itself only
        // when the classpath element is that whole file
        return nioPath != null && root.getPath().equals(FileUtils.pathStr(nioPath)) ? nioPath : null;
    }

    /**
     * The path of a child classpath entry that lies beside the classpath element that declared it, in the same
     * filesystem as that classpath element.
     *
     * @param containerNioPath
     *            the path of the classpath element that declared it, or null if it does not have one.
     * @param parentDir
     *            the directory that contains the classpath element.
     * @param location
     *            the resolved location of the child classpath entry.
     * @return the path of the child classpath entry, or null if it does not lie under any directory above the
     *         classpath element (a {@code Class-Path} entry may be an absolute path or a URL somewhere else
     *         entirely), or if its location cannot be spelled as a path of that filesystem.
     */
    private static @Nullable Path siblingPath(final @Nullable Path containerNioPath, final String parentDir,
            final String location) {
        if (containerNioPath == null) {
            return null;
        }
        // The location was resolved against the directory that contains the classpath element, so walking up from
        // that directory as far as the two spellings of it differ, and then down the rest of the location, spells
        // the child classpath entry as a path of the same filesystem -- which is the only way to reach one that is
        // not in the default filesystem. A "../" in a Class-Path entry is what makes the walk necessary: it
        // resolves to somewhere above the directory that contains the classpath element.
        var dir = parentDir;
        var dirPath = containerNioPath.getParent();
        while (!location.startsWith(dir + "/")) {
            if (dir.isEmpty() || dirPath == null) {
                return null;
            }
            dir = PathSyntax.getParentDirPath(dir);
            dirPath = dirPath.getParent();
        }
        if (dirPath == null) {
            return null;
        }
        try {
            return dirPath.resolve(location.substring(dir.length() + 1));
        } catch (final InvalidPathException e) {
            return null;
        }
    }

    /**
     * The path of a child classpath entry that lies within the classpath element that declared it, in the same
     * filesystem as that classpath element.
     *
     * @param containerNioPath
     *            the path of the classpath element that declared it, or null if it does not have one.
     * @param nameWithin
     *            the name of the child classpath entry, relative to the classpath element.
     * @return the path of the child classpath entry, or null if the classpath element does not have one, or if the
     *         name cannot be spelled as a path of that filesystem.
     */
    private static @Nullable Path resolveWithin(final @Nullable Path containerNioPath, final String nameWithin) {
        if (containerNioPath == null) {
            return null;
        }
        try {
            return containerNioPath.resolve(nameWithin);
        } catch (final InvalidPathException e) {
            return null;
        }
    }
}
