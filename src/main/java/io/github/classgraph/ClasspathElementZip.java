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
package io.github.classgraph;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceException;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.FastZipEntry;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;
import org.jspecify.annotations.Nullable;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    /**
     * The POSIX file permissions corresponding to the nine mode bits of a zip entry's external file attributes, in
     * bit order, from the most significant bit ({@code 0400}) to the least significant ({@code 0001}).
     */
    private static final PosixFilePermission[] POSIX_FILE_PERMISSION_BITS = { PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE };

    /**
     * The {@link String} representation of the path string, {@link URL}, {@link URI}, or {@link Path} for this
     * zipfile.
     */
    private final String rawPath;
    /**
     * The logical zipfile for this classpath element, or null until {@link #open} has been called (or if the
     * classpath element could not be opened).
     */
    @Nullable
    LogicalZipFile logicalZipFile;
    /**
     * The normalized path of the jarfile, "!/"-separated if nested, excluding any package root.
     */
    private String zipFilePath;
    /**
     * A map from relative path to {@link Resource} for non-rejected zip entries.
     */
    private final ConcurrentHashMap<String, Resource> relativePathToResource = new ConcurrentHashMap<>();
    /**
     * A list of all automatic package root prefixes found as prefixes of paths within this zipfile.
     */
    private final Set<String> strippedAutomaticPackageRootPrefixes = new HashSet<>();
    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;
    /**
     * The name of the module from the {@code Automatic-Module-Name} manifest attribute, if one is present in the
     * root of the classpath element.
     */
    @Nullable
    String moduleNameFromManifestFile;
    /** The automatic module name, derived from the jarfile filename. */
    private @Nullable String derivedAutomaticModuleName;

    /**
     * A jarfile classpath element.
     *
     * @param workUnit
     *            the work unit
     * @param nestedJarHandler
     *            the nested jar handler
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementZip(final ClasspathEntryWorkUnit workUnit, final NestedJarHandler nestedJarHandler,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        final var rawPathObj = Objects.requireNonNull(workUnit.classpathEntryObj);

        // Convert the raw path object (Path, URL, or URI) to a string. Any required URL/URI parsing are done in
        // NestedJarHandler.
        String rawPath = null;
        if (rawPathObj instanceof final Path path) {
            // Path.toString does not include URI scheme => turn into a URI so that toString works
            try {
                rawPath = path.toUri().toString();
            } catch (final IOError | SecurityException e) {
                // Fall through
            }
        }
        if (rawPath == null) {
            rawPath = rawPathObj.toString();
        }
        this.rawPath = rawPath;
        this.zipFilePath = rawPath; // May change when open() is called
        this.nestedJarHandler = nestedJarHandler;
    }

    /**
     * Schedules the child classpath elements found within this classpath element -- nested lib jars, and the
     * entries of the manifest's {@code Class-Path} and {@code Bundle-ClassPath} attributes -- for scanning.
     */
    private final class ChildClasspathElementScheduler {
        /** The work queue to add child classpath elements to. */
        private final WorkQueue<ClasspathEntryWorkUnit> workQueue;
        /**
         * The child classpath elements that have already been scheduled, so that no child classpath element is
         * scheduled twice, and so that this classpath element is not scheduled as a child of itself.
         */
        private final Set<String> alreadyScheduled = new HashSet<>();
        /** The order of the next child classpath element within this classpath element. */
        private int childClasspathEntryIdx;

        ChildClasspathElementScheduler(final WorkQueue<ClasspathEntryWorkUnit> workQueue) {
            this.workQueue = workQueue;
            alreadyScheduled.add(rawPath);
        }

        /**
         * Schedule a child classpath element for scanning, unless it has already been scheduled.
         *
         * @param childClasspathEltPath
         *            the path of the child classpath element
         * @throws InterruptedException
         *             if the thread was interrupted
         */
        void schedule(final String childClasspathEltPath) throws InterruptedException {
            if (alreadyScheduled.add(childClasspathEltPath)) {
                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(childClasspathEltPath, getClassLoader(),
                        /* parentClasspathElement = */ ClasspathElementZip.this,
                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                        /* packageRootPrefix = */ "", packageRootPrefixes));
            }
        }
    }

    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanJars) {
            if (log != null) {
                log(classpathElementIdx, "Skipping classpath element, since jar scanning is disabled: " + rawPath,
                        log);
            }
            skipClasspathElement = true;
            return;
        }
        final var subLog = log == null ? null : log(classpathElementIdx, "Opening jar: " + rawPath, log);

        final var logicalZipFile = openLogicalZipFile(subLog);
        if (logicalZipFile == null) {
            skipClasspathElement = true;
            return;
        }

        final var childScheduler = new ChildClasspathElementScheduler(workQueue);
        addNestedLibJars(logicalZipFile, childScheduler, subLog);
        addClassPathManifestEntries(logicalZipFile, childScheduler);
        addBundleClassPathManifestEntries(logicalZipFile, childScheduler);
    }

    /**
     * Open the {@link LogicalZipFile} for this classpath element, and record its normalized path and package root.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the {@link LogicalZipFile}, or null if the zipfile could not be opened, or if it should not be
     *         scanned.
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private @Nullable LogicalZipFile openLogicalZipFile(final @Nullable LogNode log) throws InterruptedException {
        final var plingIdx = JarUtils.indexOfNestedJarSeparator(rawPath);
        final var outermostZipFilePathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                plingIdx < 0 ? rawPath : rawPath.substring(0, plingIdx));
        if (!scanSpec.jarAcceptReject.isAcceptedAndNotRejected(outermostZipFilePathResolved)) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }

        final LogicalZipFile logicalZipFile;
        try {
            // Get LogicalZipFile for innermost nested jarfile
            final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot;
            try {
                logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                        .get(rawPath, log);
            } catch (final NullSingletonException | NewInstanceException e) {
                // Generally thrown on the second and subsequent attempt to call .get(), after the first failed, or
                // newInstance() threw an exception
                throw new IOException("Could not get logical zipfile " + rawPath + " : "
                        + (e.getCause() == null ? e : e.getCause()));
            }
            this.logicalZipFile = logicalZipFile = logicalZipFileAndPackageRoot.getKey();

            // Get the normalized path of the logical zipfile
            zipFilePath = FastPathResolver.resolve(FileUtils.currDirPath(), logicalZipFile.getPath());

            // Get package root of jarfile
            final var packageRoot = logicalZipFileAndPackageRoot.getValue();
            if (!packageRoot.isEmpty()) {
                packageRootPrefix = packageRoot + "/";
            }
        } catch (final IOException | IllegalArgumentException e) {
            if (log != null) {
                log.log("Could not open jarfile " + rawPath + " : " + e);
            }
            return null;
        }

        if (!scanSpec.enableSystemJarsAndModules && logicalZipFile.isJREJar) {
            // Found a rejected JRE jar that was not caught by filtering for rt.jar in ClasspathFinder (the isJREJar
            // value was set by detecting JRE headers in the jar's manifest file)
            if (log != null) {
                log.log("Ignoring JRE jar: " + rawPath);
            }
            return null;
        }

        if (!logicalZipFile.isAcceptedAndNotRejected(scanSpec.jarAcceptReject)) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }
        return logicalZipFile;
    }

    /**
     * Automatically add any nested "lib/" dirs to the classpath, since not all classloaders return them as
     * classpath elements.
     *
     * @param logicalZipFile
     *            the logical zipfile
     * @param childScheduler
     *            the child classpath element scheduler
     * @param log
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void addNestedLibJars(final LogicalZipFile logicalZipFile,
            final ChildClasspathElementScheduler childScheduler, final @Nullable LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanNestedJars) {
            return;
        }
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                // Even if a package root is given, e.g. BOOT-INF/classes, still look in lib/ etc. for jars
                if (zipEntry.entryNameUnversioned.startsWith(libDirPrefix)
                        && zipEntry.entryNameUnversioned.endsWith(".jar")) {
                    final var entryPath = zipEntry.getPath();
                    if (log != null) {
                        log.log("Found nested lib jar: " + entryPath);
                    }
                    childScheduler.schedule(entryPath);
                    break;
                }
            }
        }
    }

    /**
     * Create child classpath elements from the values of the manifest's {@code Class-Path} attribute, resolving the
     * paths relative to the dir or parent jarfile that this jarfile is contained in.
     *
     * @param logicalZipFile
     *            the logical zipfile
     * @param childScheduler
     *            the child classpath element scheduler
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void addClassPathManifestEntries(final LogicalZipFile logicalZipFile,
            final ChildClasspathElementScheduler childScheduler) throws InterruptedException {
        if (logicalZipFile.classPathManifestEntryValue == null) {
            return;
        }
        // Get parent dir of logical zipfile within grandparent slice, e.g. for a zipfile slice path of
        // "/path/to/jar1.jar!/lib/jar2.jar", this is "lib", or for "/path/to/jar1.jar", this is "/path/to", or
        // "" if the jar is in the toplevel dir.
        final var jarParentDir = FileUtils.getParentDirPath(logicalZipFile.getPathWithinParentZipFileSlice());
        for (final String childClassPathEltPathRelative : logicalZipFile.classPathManifestEntryValue.split(" ")) {
            if (!childClassPathEltPathRelative.isEmpty()) {
                // Resolve Class-Path entry relative to containing dir
                final var childClassPathEltPath = FastPathResolver.resolve(jarParentDir,
                        childClassPathEltPathRelative);
                // If this is a nested jar, prepend outer jar prefix
                final var parentZipFileSlice = logicalZipFile.getParentZipFileSlice();
                final var childClassPathEltPathWithPrefix = parentZipFileSlice == null ? childClassPathEltPath
                        : parentZipFileSlice.getPath() + (childClassPathEltPath.startsWith("/") ? "!" : "!/")
                                + childClassPathEltPath;
                childScheduler.schedule(childClassPathEltPathWithPrefix);
            }
        }
    }

    /**
     * Add the paths in an OSGi bundle jar manifest's {@code Bundle-ClassPath} attribute to the classpath, resolving
     * the paths relative to the root of the jarfile.
     *
     * @param logicalZipFile
     *            the logical zipfile
     * @param childScheduler
     *            the child classpath element scheduler
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void addBundleClassPathManifestEntries(final LogicalZipFile logicalZipFile,
            final ChildClasspathElementScheduler childScheduler) throws InterruptedException {
        if (logicalZipFile.bundleClassPathManifestEntryValue == null) {
            return;
        }
        final var zipFilePathPrefix = zipFilePath + "!/";
        // Class-Path is split on " ", but Bundle-ClassPath is split on ","
        for (String childBundlePath : logicalZipFile.bundleClassPathManifestEntryValue.split(",")) {
            // Assume that Bundle-ClassPath paths have to be given relative to jarfile root
            while (childBundlePath.startsWith("/")) {
                childBundlePath = childBundlePath.substring(1);
            }
            // Currently the position of "." relative to child classpath entries is ignored (the Bundle-ClassPath
            // path is treated as if "." is in the first position, since child classpath entries are always added
            // to the classpath after the parent classpath entry that they were obtained from).
            if (!childBundlePath.isEmpty() && !".".equals(childBundlePath)) {
                // Resolve Bundle-ClassPath entry within jar
                childScheduler.schedule(zipFilePathPrefix + FileUtils.sanitizeEntryPath(childBundlePath,
                        /* removeInitialSlash = */ true, /* removeFinalSlash = */ true));
            }
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param zipEntry
     *            the zip entry
     * @param pathRelativeToPackageRoot
     *            the path relative to package root
     * @return the resource
     */
    private Resource newResource(final FastZipEntry zipEntry, final String pathRelativeToPackageRoot) {
        return new Resource(this, zipEntry.uncompressedSize) {
            /**
             * Path with package root prefix and/or any Spring Boot prefix ("BOOT-INF/classes/" or
             * "WEB-INF/classes/") removed.
             */
            @Override
            public String getPath() {
                return pathRelativeToPackageRoot;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                if (zipEntry.entryName.startsWith(packageRootPrefix)) {
                    return zipEntry.entryName.substring(packageRootPrefix.length());
                } else {
                    return zipEntry.entryName;
                }
            }

            @Override
            public long getLastModifiedMillis() {
                return zipEntry.getLastModifiedTimeMillis();
            }

            @Override
            public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
                final var fileAttributes = zipEntry.fileAttributes;
                if (fileAttributes == 0) {
                    // Zip entries written by tools that do not record Unix mode bits have zero file attributes
                    return null;
                }
                final Set<PosixFilePermission> perms = new HashSet<>();
                for (var i = 0; i < POSIX_FILE_PERMISSION_BITS.length; i++) {
                    if ((fileAttributes & (0400 >> i)) != 0) {
                        perms.add(POSIX_FILE_PERMISSION_BITS[i]);
                    }
                }
                return perms;
            }

            @Override
            ClassfileReader openClassfile() throws IOException {
                return new ClassfileReader(open(), this);
            }

            @Override
            public InputStream open() throws IOException {
                checkCanOpen();
                try {
                    inputStream = zipEntry.getSlice().open(this);
                    length = zipEntry.uncompressedSize;
                    return inputStream;

                } catch (final IOException e) {
                    close();
                    throw e;
                }
            }

            @Override
            public ByteBuffer read() throws IOException {
                checkCanOpen();
                try {
                    byteBuffer = zipEntry.getSlice().read();
                    length = byteBuffer.remaining();
                    return byteBuffer;
                } catch (final IOException e) {
                    close();
                    throw e;
                }
            }

            @Override
            public byte[] load() throws IOException {
                checkCanOpen();
                try (Resource res = this) { // Close this after use
                    final var byteArray = zipEntry.getSlice().load();
                    res.length = byteArray.length;
                    return byteArray;
                }
            }

            @Override
            public void close() {
                if (markClosed()) {
                    if (byteBuffer != null) {
                        // ByteBuffer should be a duplicate or slice, or should wrap an array, so it doesn't need to
                        // be unmapped
                        byteBuffer = null;
                    }

                    // Close inputStream
                    super.close();
                }
            }
        };
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        return relativePathToResource.get(relativePath);
    }

    /**
     * Filter out any candidate package root prefix that is really a package with the same name as the prefix, e.g.
     * a package named {@code classes} in a jar that has no {@code classes/} package root.
     *
     * @param logicalZipFile
     *            the logical zipfile
     * @param log
     *            the log node, or null to skip logging
     * @return the package root prefixes that were not disproved
     */
    // #929
    private String[] getVerifiedPackageRootPrefixes(final LogicalZipFile logicalZipFile,
            final @Nullable LogNode log) {
        // Find the first classfile beneath each candidate package root prefix
        final var firstClassfileEntry = new FastZipEntry[packageRootPrefixes.length];
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            final var entryName = zipEntry.entryNameUnversioned;
            if (!entryName.endsWith(".class")) {
                continue;
            }
            for (var i = 0; i < packageRootPrefixes.length; i++) {
                final var prefix = packageRootPrefixes[i];
                if (firstClassfileEntry[i] == null && entryName.startsWith(prefix)
                // The path of a classfile below META-INF (e.g. in a multi-release jar) does not necessarily
                // correspond to the name of the class it declares
                        && !entryName.startsWith("META-INF/", prefix.length())) {
                    firstClassfileEntry[i] = zipEntry;
                }
            }
        }
        // Check the class declared by each of those classfiles against its path
        final List<String> verifiedPackageRootPrefixes = new ArrayList<>(packageRootPrefixes.length);
        for (var i = 0; i < packageRootPrefixes.length; i++) {
            final var prefix = packageRootPrefixes[i];
            final var zipEntry = firstClassfileEntry[i];
            String disprovingClassName = null;
            if (zipEntry != null) {
                try (var classfileReader = new ClassfileReader(zipEntry.getSlice(), /* resourceToClose = */ null)) {
                    disprovingClassName = getClassNameDisprovingPackageRoot(classfileReader,
                            zipEntry.entryNameUnversioned.substring(prefix.length()));
                } catch (final IOException e) {
                    // If the classfile cannot be read, give the candidate package root the benefit of the doubt
                }
            }
            if (disprovingClassName == null) {
                verifiedPackageRootPrefixes.add(prefix);
            } else if (log != null) {
                log.log("\"" + prefix + "\" is a package, not a package root, since a classfile beneath it "
                        + "declares the class " + disprovingClassName);
            }
        }
        return verifiedPackageRootPrefixes.toArray(String[]::new);
    }

    /**
     * Scan for path matches within jarfile, and record ZipEntry objects of matching files.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void scanPaths(final @Nullable LogNode log) {
        if (this.logicalZipFile == null) {
            skipClasspathElement = true;
        }
        if (!checkResourcePathAcceptReject(getZipFilePath(), log)) {
            skipClasspathElement = true;
        }
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalStateException("Already scanned classpath element " + getZipFilePath());
        }

        final var logicalZipFile = Objects.requireNonNull(this.logicalZipFile);

        final var subLog = log == null ? null
                : log(classpathElementIdx, "Scanning jarfile classpath element " + getZipFilePath(), log);

        // A jar is modular only if it declares a module name -- an automatic module name derived from the jar name
        // does not make the jar modular
        final var isModularJar = getDeclaredModuleName() != null;

        // "classes/" and "test-classes/" are legal package names, so only strip a package root prefix from the
        // relative path of an entry if the prefix is not simply a package with the same name (#929)
        final var verifiedPackageRootPrefixes = packageRootPrefix.isEmpty() && packageRootPrefixes.length > 0
                ? getVerifiedPackageRootPrefixes(logicalZipFile, subLog)
                : packageRootPrefixes;

        final Set<String> loggedNestedClasspathRootPrefixes = new HashSet<>();
        final var parentDirMatchStatusCache = new ParentDirMatchStatusCache();
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            final var entryName = zipEntry.entryNameUnversioned;

            if (isIgnoredVersionedPath(entryName)) {
                if (subLog != null) {
                    subLog.log("Found unexpected versioned entry in jar (the jar's manifest file may be missing "
                            + "the \"Multi-Release\" key) -- skipping: " + entryName);
                }
                continue;
            }

            if (isIgnoredDefaultPackageClassfile(isModularJar, entryName)) {
                continue;
            }

            if (isWithinNestedClasspathRoot(entryName, loggedNestedClasspathRootPrefixes, subLog)) {
                continue;
            }

            final var relativePath = stripPackageRootPrefix(entryName, verifiedPackageRootPrefixes);
            if (relativePath == null) {
                // Entry does not have the required package root prefix
                continue;
            }

            // Accept/reject classpath elements based on file resource paths
            if (!checkResourcePathAcceptReject(relativePath, subLog)) {
                continue;
            }

            final var parentMatchStatus = parentDirMatchStatusCache.getParentMatchStatus(relativePath);
            if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
                // The parent dir or one of its ancestral dirs is rejected
                if (subLog != null) {
                    subLog.log("Skipping rejected path: " + relativePath);
                }
                continue;
            }

            addZipEntryResource(zipEntry, relativePath, parentMatchStatus, subLog);
        }

        // Save the last modified time for the zipfile
        final var zipfile = getFile();
        if (zipfile != null) {
            fileToLastModified.put(zipfile, zipfile.lastModified());
        }

        finishScanPaths(subLog);
    }

    /**
     * Check whether a zip entry is within a nested classpath root, i.e. within a classpath element that is nested
     * inside this classpath element, and that will therefore be scanned separately.
     *
     * @param entryName
     *            the name of the zip entry
     * @param loggedNestedClasspathRootPrefixes
     *            the nested classpath roots that have already been logged, so that each is logged only once
     * @param log
     *            the log node, or null to skip logging
     * @return true if the entry is within a nested classpath root, and should therefore not be scanned
     */
    private boolean isWithinNestedClasspathRoot(final String entryName,
            final Set<String> loggedNestedClasspathRootPrefixes, final @Nullable LogNode log) {
        if (nestedClasspathRootPrefixes == null) {
            return false;
        }
        // This is O(mn), which is inefficient, but the number of nested classpath roots should be small
        for (final String nestedClasspathRoot : nestedClasspathRootPrefixes) {
            if (entryName.startsWith(nestedClasspathRoot)) {
                if (log != null && loggedNestedClasspathRootPrefixes.add(nestedClasspathRoot)) {
                    log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                            + nestedClasspathRoot);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Strip the package root prefix from the name of a zip entry, to give the path of the entry relative to the
     * package root.
     *
     * @param entryName
     *            the name of the zip entry
     * @param verifiedPackageRootPrefixes
     *            the automatic package root prefixes to strip, if this classpath element has no explicit package
     *            root
     * @return the path of the entry relative to the package root, or null if the entry is not within the package
     *         root of this classpath element
     */
    private @Nullable String stripPackageRootPrefix(final String entryName,
            final String[] verifiedPackageRootPrefixes) {
        if (!packageRootPrefix.isEmpty()) {
            // Ignore entries without the correct classpath root prefix
            return entryName.startsWith(packageRootPrefix) ? entryName.substring(packageRootPrefix.length()) : null;
        }
        // Strip any automatic package root prefix from the entry name
        for (final String packageRoot : verifiedPackageRootPrefixes) {
            if (entryName.startsWith(packageRoot)) {
                // Strip final slash from package root, and store the package root for use by getAllURIs()
                strippedAutomaticPackageRootPrefixes
                        .add(packageRoot.endsWith("/") ? packageRoot.substring(0, packageRoot.length() - 1)
                                : packageRoot);
                // Only one package root prefix can be stripped from a given path
                return entryName.substring(packageRoot.length());
            }
        }
        return entryName;
    }

    /**
     * Add a zip entry as a {@link Resource}, and, if the resource is accepted, schedule it for scanning.
     *
     * @param zipEntry
     *            the zip entry
     * @param relativePath
     *            the path of the entry relative to the package root
     * @param parentMatchStatus
     *            the match status of the parent dir of the entry
     * @param log
     *            the log node, or null to skip logging
     */
    private void addZipEntryResource(final FastZipEntry zipEntry, final String relativePath,
            final ScanSpecPathMatch parentMatchStatus, final @Nullable LogNode log) {
        final var resource = newResource(zipEntry, relativePath);
        if (relativePathToResource.putIfAbsent(relativePath, resource) == null) {
            if (isAcceptedResourcePath(relativePath, parentMatchStatus)) {
                // Resource is accepted
                addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, log);
            } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
                // Add module descriptor as an accepted classfile resource, so that it is scanned, but don't add it
                // to the list of resources in the ScanResult, since it is not in an accepted package (#352)
                addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, log);
            }
        }
    }

    /**
     * Get the module name declared by the jarfile, either by its {@code module-info.class} module descriptor, or by
     * the {@code Automatic-Module-Name} attribute of its manifest file.
     *
     * @return the declared module name, or null if the jarfile does not declare one.
     */
    private @Nullable String getDeclaredModuleName() {
        if (moduleNameFromModuleDescriptor != null && !moduleNameFromModuleDescriptor.isEmpty()) {
            return moduleNameFromModuleDescriptor;
        }
        return moduleNameFromManifestFile == null || moduleNameFromManifestFile.isEmpty() ? null
                : moduleNameFromManifestFile;
    }

    /**
     * Get the module name declared by the jarfile, or, if it declares none, an automatic module name derived from
     * the jar name.
     *
     * @return the module name
     */
    @Override
    public @Nullable String getModuleName() {
        final var declaredModuleName = getDeclaredModuleName();
        if (declaredModuleName != null) {
            return declaredModuleName;
        }
        if (derivedAutomaticModuleName == null) {
            derivedAutomaticModuleName = JarUtils.derivedAutomaticModuleName(zipFilePath);
        }
        return derivedAutomaticModuleName == null || derivedAutomaticModuleName.isEmpty() ? null
                : derivedAutomaticModuleName;
    }

    /**
     * Get the zipfile path.
     *
     * @return the path of the zipfile, including any package root.
     */
    String getZipFilePath() {
        return packageRootPrefix.isEmpty() ? zipFilePath
                : zipFilePath + "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
    }

    @Override
    URI getURI() {
        try {
            return new URI(URLPathEncoder.normalizeURLPath(getZipFilePath()));
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI: " + e);
        }
    }

    /**
     * Return URI for classpath element, plus URIs for any stripped nested automatic package root prefixes, e.g.
     * "!/BOOT-INF/classes".
     */
    @Override
    List<URI> getAllURIs() {
        if (strippedAutomaticPackageRootPrefixes.isEmpty()) {
            return List.of(getURI());
        } else {
            final var uri = getURI();
            final List<URI> uris = new ArrayList<>();
            uris.add(uri);
            final var uriStr = uri.toString();
            for (final String prefix : strippedAutomaticPackageRootPrefixes) {
                try {
                    uris.add(new URI(uriStr + "!/" + prefix));
                } catch (final URISyntaxException e) {
                    // Ignore
                }
            }
            return uris;
        }
    }

    /**
     * Get the {@link File} for the outermost zipfile of this classpath element.
     *
     * @return The {@link File} for the outermost zipfile of this classpath element, or null if this file was
     *         downloaded from a URL directly to RAM, or if the classpath element was backed by a custom filesystem
     *         that supports the {@link Path} API put not the {@link File} API.
     */
    @Override
    @Nullable
    File getFile() {
        if (logicalZipFile != null) {
            return logicalZipFile.getPhysicalFile();
        } else {
            // Not performing a full scan (only getting classpath elements), so logicalZipFile is not set
            final var plingIdx = JarUtils.indexOfNestedJarSeparator(rawPath);
            final var outermostZipFilePathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                    plingIdx < 0 ? rawPath : rawPath.substring(0, plingIdx));
            return new File(outermostZipFilePathResolved);
        }
    }

    /**
     * Return the classpath element path.
     *
     * @return the string
     */
    @Override
    public String toString() {
        return getZipFilePath();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementZip other)) {
            return false;
        }
        return this.getZipFilePath().equals(other.getZipFilePath());
    }

    @Override
    public int hashCode() {
        return getZipFilePath().hashCode();
    }
}
