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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.ArchiveRoot;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;

/**
 * Extracts child classpath entries declared by a classpath element via its manifest {@code Class-Path} and
 * {@code Bundle-ClassPath} attributes and its automatic lib directories.
 */
public final class ClasspathExpander {
    /** The manifest {@code Class-Path} attribute name. */
    private static final String CLASS_PATH_KEY = "Class-Path";

    /** The OSGi manifest {@code Bundle-ClassPath} attribute name. */
    private static final String BUNDLE_CLASS_PATH_KEY = "Bundle-ClassPath";

    /** Not instantiable. */
    private ClasspathExpander() {
        // Cannot be constructed
    }

    /** Source of a child classpath entry declared by a parent classpath element. */
    public enum ChildEntryOrigin {
        /** A jarfile found in an automatic lib directory. */
        NESTED_LIB_JAR("Found nested lib jar"),

        /** An entry from the manifest {@code Class-Path} attribute. */
        CLASS_PATH_MANIFEST_ENTRY("Found Class-Path manifest entry"),

        /** An entry from the manifest {@code Bundle-ClassPath} attribute. */
        BUNDLE_CLASS_PATH_MANIFEST_ENTRY("Found Bundle-ClassPath manifest entry");

        /** Log message prefix for this origin. */
        private final String logMessage;

        /**
         * Constructor.
         *
         * @param logMessage
         *            the log message prefix.
         */
        ChildEntryOrigin(final String logMessage) {
            this.logMessage = logMessage;
        }

        /**
         * Returns the log message prefix for this origin.
         *
         * @return the log message prefix.
         */
        public String getLogMessage() {
            return logMessage;
        }
    }

    /**
     * A child classpath entry declared by a parent classpath element.
     *
     * @param origin
     *            how the child entry was declared.
     * @param location
     *            the resolved location of the child entry.
     * @param path
     *            the child entry as a {@link Path} in the parent element's filesystem, or null if none.
     */
    public record ChildEntry(ChildEntryOrigin origin, String location, @Nullable Path path) {
    }

    /**
     * Returns the child classpath entries declared by {@code root}, in classpath resolution order.
     *
     * @param root
     *            the opened classpath element.
     * @param libDirPrefixes
     *            automatic lib directory prefixes (each ending in {@code '/'}).
     * @param enableNestedJars
     *            whether jarfiles nested inside archive classpath elements should be included.
     * @param log
     *            the log node, or null to skip logging.
     * @return the child classpath entries in declaration order.
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
        // found in one of its lib dirs, since an earlier classpath element masks a later one, and a classloader
        // that serves an archive with both loads a class from the archive's own classes in preference to a copy of
        // it in a bundled dependency -- Tomcat looks in "WEB-INF/classes/" before "WEB-INF/lib/", and Spring Boot's
        // launcher looks in "BOOT-INF/classes/" before "BOOT-INF/lib/". (The package root itself is not a child
        // classpath entry, and is added by the caller, ahead of all of these.)
        addClassPathManifestEntries(container, childEntries, log);
        addBundleClassPathManifestEntries(container, childEntries);
        if (enableNestedJars || !(container instanceof ArchiveRoot)) {
            addLibJars(container, libDirPrefixes, childEntries);
        }
        return childEntries;
    }

    /**
     * Adds {@code .jar} files found in {@code libDirPrefixes} within {@code container} to {@code childEntries}.
     *
     * @param container
     *            the opened container root.
     * @param libDirPrefixes
     *            the lib directory prefixes (each ending in {@code '/'}).
     * @param childEntries
     *            the list to append child entries to.
     * @throws IOException
     *             if the container entries could not be listed.
     */
    private static void addLibJars(final VfsRoot container, final List<String> libDirPrefixes,
            final List<ChildEntry> childEntries) throws IOException {
        // A jarfile can lie in more than one of the lib dirs only if one of them is below another, but list the
        // names that have been added anyway, since adding the same jarfile to the classpath twice would make it
        // mask itself
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
     * Parses and resolves manifest {@code Class-Path} entries from {@code container} and adds them to
     * {@code childEntries}.
     *
     * @param container
     *            the opened container root.
     * @param childEntries
     *            the list to append child entries to.
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
        // there rather than against the whole path is what keeps a ".." from climbing out of that jarfile. A "!/"
        // before the outermost jarfile, as at the end of a directory named "dir!", is not a separator, so the
        // separators are found by testing the filesystem, as they were when the path was resolved.
        final var containerPath = container.getPath();
        final var separatorIdx = PathSyntax.lastIndexOfNestedJarSeparator(containerPath);
        final var outerPath = separatorIdx < 0 ? null : containerPath.substring(0, separatorIdx);
        final var parentDir = PathSyntax
                .getParentDirPath(separatorIdx < 0 ? containerPath : containerPath.substring(separatorIdx + 2));
        final var containerNioPath = pathOfRoot(container);
        // The JVM's own classloader treats the Class-Path entries of a jarfile named by a file: URL differently
        // from those of a jarfile named by any other URL. Only a jarfile of the filesystem can name an entry by a
        // URL, and only by a file: URL. A jarfile fetched from a URL can only name entries relative to the
        // directory it was fetched from, so it cannot name a file of the filesystem. A jarfile nested inside
        // another jarfile cannot name an entry by a URL either, but a path in it that starts with '/' names a path
        // from the root of the jarfile that contains it, and a ".." in it stops at that root. (A jarfile in a
        // filesystem other than the default one is named by a URL too, but it is a file of a filesystem, so it is
        // treated as one.)
        final var isFetchedFromURL = outerPath == null && containerNioPath == null
                && URLPaths.startsWithURLScheme(containerPath);
        final var mayNameAFileURL = outerPath == null && !isFetchedFromURL;
        // Any whitespace separates the entries, as for the JVM's own classloader, not only a space
        for (final String relativePath : classPath.split("[ \t\n\r\f]+")) {
            if (relativePath.isEmpty()) {
                continue;
            }
            final var hasURLScheme = URLPaths.startsWithURLScheme(relativePath);
            if (hasURLScheme && !(mayNameAFileURL && relativePath.regionMatches(true, 0, "file:", 0, 5))) {
                if (log != null) {
                    log.log("Ignoring Class-Path entry " + relativePath + " of " + containerPath + ", since "
                            + (mayNameAFileURL ? "the JVM ignores a URL with a scheme other than file:"
                                    : isFetchedFromURL ? "a jarfile fetched from a URL cannot name a URL"
                                            : "a jarfile nested inside another jarfile cannot name a URL"));
                }
                continue;
            }
            // A Class-Path entry is a relative URL, so its percent encoding is decoded, as the JVM's own
            // classloader decodes it -- a jarfile with a space in its name cannot be named any other way, since
            // whitespace separates the entries. A file: URL is decoded by the resolver.
            final var urlPath = hasURLScheme ? relativePath : FastPathResolver.decodePercentEncoding(relativePath);
            final var resolved = FastPathResolver.resolve(parentDir, urlPath);
            if (isFetchedFromURL && (!isValidRelativeURL(relativePath) || !resolved.startsWith(parentDir + "/")
                    || resolved.indexOf("..", parentDir.length()) >= 0)) {
                // An entry that is not a valid relative URL, an absolute path, a relative path that climbs above
                // the directory the jarfile was fetched from, or a path below it with ".." anywhere in it, even
                // within a name such as "a..b.jar": the JVM ignores all but the absolute path. (The JVM resolves a
                // path that starts with '/' against the root of the server, and follows it if it lies below the
                // jarfile's directory. Such a path is ignored here instead, which never follows an entry that the
                // JVM would not.)
                if (log != null) {
                    log.log("Ignoring Class-Path entry " + relativePath + " of " + containerPath
                            + ", since a jarfile fetched from a URL can only name paths below the directory it was"
                            + " fetched from, by a valid relative URL with no \"..\" in it");
                }
                continue;
            }
            // If the classpath element is nested inside a jarfile, so is anything beside it
            final var location = outerPath == null ? resolved
                    : outerPath + (resolved.startsWith("/") ? "!" : "!/") + resolved;
            childEntries.add(new ChildEntry(ChildEntryOrigin.CLASS_PATH_MANIFEST_ENTRY, location,
                    outerPath != null ? null : siblingPath(containerNioPath, parentDir, location)));
        }
    }

    /**
     * Checks whether {@code relativePath} is a valid relative URI.
     *
     * @param relativePath
     *            the {@code Class-Path} entry string.
     * @return true if {@code relativePath} is a valid relative URI.
     */
    private static boolean isValidRelativeURL(final String relativePath) {
        try {
            // The JVM replaces the platform's file separator with '/' first, so a '\' is accepted on Windows
            return !URI.create(relativePath.replace(File.separatorChar, '/')).isAbsolute();
        } catch (final IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * Parses and resolves OSGi manifest {@code Bundle-ClassPath} entries from {@code container} and adds them to
     * {@code childEntries}.
     *
     * @param container
     *            the opened container root.
     * @param childEntries
     *            the list to append child entries to.
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
        final var isArchive = container instanceof ArchiveRoot;
        final var locationPrefix = container.getPath() + (isArchive ? "!/" : "/");
        final var containerNioPath = isArchive ? null : pathOfRoot(container);
        for (String relativePath : bundleClassPathTargets(bundleClassPath)) {
            // A Bundle-ClassPath entry has to be given relative to the root of the jarfile
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            // Where "." falls among the other entries is ignored, and it is treated as if it came first, since a
            // child classpath entry is always added to the classpath after the classpath element that declared it
            if (relativePath.isEmpty() || ".".equals(relativePath)) {
                continue;
            }
            final var nameWithin = PathSyntax.sanitizeRelativePath(relativePath, /* removeFinalSlash = */ true);
            childEntries.add(new ChildEntry(ChildEntryOrigin.BUNDLE_CLASS_PATH_MANIFEST_ENTRY,
                    locationPrefix + nameWithin, resolveWithin(containerNioPath, nameWithin)));
        }
    }

    /**
     * Extracts target paths from an OSGi {@code Bundle-ClassPath} header value, stripping any parameters and
     * quotes.
     *
     * @param bundleClassPath
     *            the {@code Bundle-ClassPath} attribute value.
     * @return the target paths in declaration order.
     */
    private static List<String> bundleClassPathTargets(final String bundleClassPath) {
        final List<String> targets = new ArrayList<>();
        for (final String entry : bundleClassPath.split(",")) {
            for (final String part : entry.split(";")) {
                var target = part.strip();
                if (target.contains("=")) {
                    // A parameter ("name=value" or "name:=value"), which ends the paths of this entry
                    break;
                }
                if (target.length() >= 2 && target.startsWith("\"") && target.endsWith("\"")) {
                    target = target.substring(1, target.length() - 1);
                }
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * Returns the NIO {@link Path} of {@code root} if it directly represents a top-level file or directory, or null
     * if it is nested or stream-backed.
     *
     * @param root
     *            the opened classpath element root.
     * @return the NIO {@link Path}, or null.
     */
    private static @Nullable Path pathOfRoot(final VfsRoot root) {
        final var nioPath = root.getNioPath();
        // A root reports the path of the file it is physically stored in, which is the classpath element itself
        // only when the classpath element is that whole file
        return nioPath != null && root.getPath().equals(FileUtils.pathStr(nioPath)) ? nioPath : null;
    }

    /**
     * Resolves {@code location} as a sibling {@link Path} in the same filesystem as {@code containerNioPath}.
     *
     * @param containerNioPath
     *            the {@link Path} of the declaring classpath element, or null.
     * @param parentDir
     *            the parent directory path of the declaring classpath element.
     * @param location
     *            the resolved location of the child classpath entry.
     * @return the resolved {@link Path}, or null if it cannot be resolved in the same filesystem.
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
        } catch (final InvalidPathException _) {
            return null;
        }
    }

    /**
     * Resolves {@code nameWithin} relative to {@code containerNioPath}.
     *
     * @param containerNioPath
     *            the {@link Path} of the declaring classpath element, or null.
     * @param nameWithin
     *            the relative path within the declaring classpath element.
     * @return the resolved {@link Path}, or null if {@code containerNioPath} is null or the path is invalid.
     */
    private static @Nullable Path resolveWithin(final @Nullable Path containerNioPath, final String nameWithin) {
        if (containerNioPath == null) {
            return null;
        }
        try {
            return containerNioPath.resolve(nameWithin);
        } catch (final InvalidPathException _) {
            return null;
        }
    }
}
