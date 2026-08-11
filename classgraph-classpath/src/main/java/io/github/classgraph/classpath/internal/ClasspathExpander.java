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
package io.github.classgraph.classpath.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.classgraph.vfs.internal.zip.FastZipEntry;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;

/**
 * Finds the classpath entries that a jarfile adds to the classpath: the jarfiles in its automatic lib dirs, and the
 * entries of its manifest's {@code Class-Path} and {@code Bundle-Classpath} attributes.
 *
 * <p>
 * A classpath is not complete until these have been resolved, since each of them can in turn declare more of them.
 * The caller drives the recursion, because the two callers need different things from it: the classpath finder
 * wants the expanded list of paths, and the scanner wants a classpath element object for each one.
 *
 * <p>
 * The returned entries are in the order they must be added to the classpath, so a caller that schedules them for
 * parallel processing must record that order, and must not rely on the order in which the work finishes.
 */
public final class ClasspathExpander {
    /** Cannot be constructed. */
    private ClasspathExpander() {
        // Cannot be constructed
    }

    /** Why a jarfile declared a child classpath entry. */
    public enum ChildEntryOrigin {
        /**
         * A jarfile in one of the lib dirs that are automatically added to the classpath, e.g. {@code "lib/"}. Not
         * all classloaders list these as classpath elements, so they are found by looking for them.
         */
        NESTED_LIB_JAR("Found nested lib jar"),

        /** An entry of the manifest's {@code Class-Path} attribute. */
        CLASS_PATH_MANIFEST_ENTRY("Found Class-Path manifest entry"),

        /** An entry of the manifest's {@code Bundle-Classpath} attribute. */
        BUNDLE_CLASS_PATH_MANIFEST_ENTRY("Found Bundle-Classpath manifest entry");

        /** The text to log when an entry with this origin is found. */
        private final String logMessage;

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
     * A classpath entry declared by a jarfile.
     *
     * @param path
     *            the path of the child classpath entry, resolved so that it can be opened directly.
     * @param origin
     *            why the jarfile declared it.
     */
    public record ChildEntry(String path, ChildEntryOrigin origin) {
    }

    /**
     * Find the classpath entries that a jarfile adds to the classpath.
     *
     * @param logicalZipFile
     *            the jarfile.
     * @param zipFilePathResolved
     *            the resolved path of the jarfile, as returned by {@link FastPathResolver#resolve(String, String)}.
     * @param scanNestedLibJars
     *            whether to look for jarfiles in the automatic lib dirs.
     * @return the child classpath entries, in the order they must be added to the classpath.
     */
    public static List<ChildEntry> childEntries(final LogicalZipFile logicalZipFile,
            final String zipFilePathResolved, final boolean scanNestedLibJars) {
        final List<ChildEntry> childEntries = new ArrayList<>();
        if (scanNestedLibJars) {
            addNestedLibJars(logicalZipFile, childEntries);
        }
        addClassPathManifestEntries(logicalZipFile, childEntries);
        addBundleClassPathManifestEntries(logicalZipFile, zipFilePathResolved, childEntries);
        return childEntries;
    }

    /**
     * Find the jarfiles in the automatic lib dirs of a directory classpath element, since not all classloaders
     * return them as classpath elements.
     *
     * @param dirPath
     *            the directory.
     * @return the jarfiles in the directory's automatic lib dirs, in the order they must be added to the classpath.
     * @throws SecurityException
     *             if the directory could not be read.
     */
    public static List<Path> libJarsInDir(final Path dirPath) {
        final List<Path> libJars = new ArrayList<>();
        for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
            final var libDirPath = dirPath.resolve(libDirPrefix);
            if (FileUtils.canReadAndIsDir(libDirPath)) {
                try (var stream = Files.newDirectoryStream(libDirPath,
                        filePath -> filePath.toString().toLowerCase().endsWith(".jar")
                                && Files.isRegularFile(filePath))) {
                    for (final Path filePath : stream) {
                        libJars.add(filePath);
                    }
                } catch (final IOException e) {
                    // Ignore -- thrown by Files.newDirectoryStream
                }
            }
        }
        return libJars;
    }

    /**
     * Add any jarfiles in the automatic lib dirs, since not all classloaders return them as classpath elements.
     *
     * @param logicalZipFile
     *            the jarfile.
     * @param childEntries
     *            the list to add the child classpath entries to.
     */
    private static void addNestedLibJars(final LogicalZipFile logicalZipFile, final List<ChildEntry> childEntries) {
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                // Even if a package root is given, e.g. BOOT-INF/classes, still look in lib/ etc. for jars
                if (zipEntry.entryNameUnversioned.startsWith(libDirPrefix)
                        && zipEntry.entryNameUnversioned.endsWith(".jar")) {
                    childEntries.add(new ChildEntry(zipEntry.getPath(), ChildEntryOrigin.NESTED_LIB_JAR));
                    break;
                }
            }
        }
    }

    /**
     * Add the entries of the manifest's {@code Class-Path} attribute, resolving the paths relative to the dir or
     * parent jarfile that this jarfile is contained in.
     *
     * @param logicalZipFile
     *            the jarfile.
     * @param childEntries
     *            the list to add the child classpath entries to.
     */
    private static void addClassPathManifestEntries(final LogicalZipFile logicalZipFile,
            final List<ChildEntry> childEntries) {
        if (logicalZipFile.classpathManifestEntryValue == null) {
            return;
        }
        // Get parent dir of logical zipfile within grandparent slice, e.g. for a zipfile slice path of
        // "/path/to/jar1.jar!/lib/jar2.jar", this is "lib", or for "/path/to/jar1.jar", this is "/path/to", or
        // "" if the jar is in the toplevel dir.
        final var jarParentDir = FileUtils.getParentDirPath(logicalZipFile.getPathWithinParentZipFileSlice());
        for (final String childClassPathEltPathRelative : logicalZipFile.classpathManifestEntryValue.split(" ")) {
            if (!childClassPathEltPathRelative.isEmpty()) {
                // Resolve Class-Path entry relative to containing dir
                final var childClassPathEltPath = FastPathResolver.resolve(jarParentDir,
                        childClassPathEltPathRelative);
                // If this is a nested jar, prepend outer jar prefix
                final var parentZipFileSlice = logicalZipFile.getParentZipFileSlice();
                final var childClassPathEltPathWithPrefix = parentZipFileSlice == null ? childClassPathEltPath
                        : parentZipFileSlice.getPath() + (childClassPathEltPath.startsWith("/") ? "!" : "!/")
                                + childClassPathEltPath;
                childEntries.add(new ChildEntry(childClassPathEltPathWithPrefix,
                        ChildEntryOrigin.CLASS_PATH_MANIFEST_ENTRY));
            }
        }
    }

    /**
     * Add the entries of an OSGi bundle jar manifest's {@code Bundle-Classpath} attribute, resolving the paths
     * relative to the root of the jarfile.
     *
     * @param logicalZipFile
     *            the jarfile.
     * @param zipFilePathResolved
     *            the resolved path of the jarfile.
     * @param childEntries
     *            the list to add the child classpath entries to.
     */
    private static void addBundleClassPathManifestEntries(final LogicalZipFile logicalZipFile,
            final String zipFilePathResolved, final List<ChildEntry> childEntries) {
        if (logicalZipFile.bundleClassPathManifestEntryValue == null) {
            return;
        }
        final var zipFilePathPrefix = zipFilePathResolved + "!/";
        // Class-Path is split on " ", but Bundle-Classpath is split on ","
        for (String childBundlePath : logicalZipFile.bundleClassPathManifestEntryValue.split(",")) {
            // Assume that Bundle-Classpath paths have to be given relative to jarfile root
            while (childBundlePath.startsWith("/")) {
                childBundlePath = childBundlePath.substring(1);
            }
            // Currently the position of "." relative to child classpath entries is ignored (the Bundle-Classpath
            // path is treated as if "." is in the first position, since child classpath entries are always added
            // to the classpath after the parent classpath entry that they were obtained from).
            if (!childBundlePath.isEmpty() && !".".equals(childBundlePath)) {
                // Resolve Bundle-Classpath entry within jar
                childEntries.add(new ChildEntry(
                        zipFilePathPrefix + FileUtils.sanitizeEntryPath(childBundlePath,
                                /* removeInitialSlash = */ true, /* removeFinalSlash = */ true),
                        ChildEntryOrigin.BUNDLE_CLASS_PATH_MANIFEST_ENTRY));
            }
        }
    }
}
