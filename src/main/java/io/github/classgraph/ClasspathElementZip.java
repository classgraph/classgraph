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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathElementAndClassLoader;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.ByteBufferWrapper;
import nonapi.io.github.classgraph.fastzipfilereader.FastZipEntry;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fastzipfilereader.ZipFileSlice;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    /** The {@link String} representation of the raw path, {@link URL} or {@link URI} for this zipfile. */
    private final String rawPath;
    /** The logical zipfile for this classpath element. */
    LogicalZipFile logicalZipFile;
    /** The package root within the jarfile. */
    private String packageRootPrefix = "";
    /** The normalized path of the jarfile, "!/"-separated if nested, excluding any package root. */
    private String zipFilePath;
    /** A map from relative path to {@link Resource} for non-blacklisted zip entries. */
    private final ConcurrentHashMap<String, Resource> relativePathToResource = new ConcurrentHashMap<>();
    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;
    /**
     * The name of the module from the {@code Automatic-Module-Name} manifest attribute, if one is present in the
     * root of the classpath element.
     */
    String moduleNameFromManifestFile;
    /** The automatic module name, derived from the jarfile filename. */
    private String derivedAutomaticModuleName;

    /**
     * A jarfile classpath element.
     *
     * @param rawPathObj
     *            the raw path to the jarfile as a {@link String}, possibly including "!"-delimited nested paths, or
     *            a {@link URL} or {@link URI} for the jarfile
     * @param classLoader
     *            the classloader
     * @param nestedJarHandler
     *            the nested jar handler
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementZip(final Object rawPathObj, final ClassLoader classLoader,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(classLoader, scanSpec);
        // Convert the raw path object (String, URL or URI) to a string.
        // Any required URL/URI parsing are done in NestedJarHandler.
        this.rawPath = rawPathObj.toString();
        this.zipFilePath = rawPath; // May change when open() is called
        this.nestedJarHandler = nestedJarHandler;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final int classpathElementIdx, final LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanJars) {
            if (log != null) {
                log(classpathElementIdx, "Skipping classpath element, since jar scanning is disabled: " + rawPath,
                        log);
            }
            skipClasspathElement = true;
            return;
        }
        final LogNode subLog = log == null ? null : log(classpathElementIdx, "Opening jar: " + rawPath, log);
        final int plingIdx = rawPath.indexOf('!');
        final String outermostZipFilePathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                plingIdx < 0 ? rawPath : rawPath.substring(0, plingIdx));
        if (!scanSpec.jarWhiteBlackList.isWhitelistedAndNotBlacklisted(outermostZipFilePathResolved)) {
            if (subLog != null) {
                subLog.log("Skipping jarfile that is blacklisted or not whitelisted: " + rawPath);
            }
            skipClasspathElement = true;
            return;
        }

        try {
            // Get LogicalZipFile for innermost nested jarfile
            Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot;
            try {
                logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap
                        .get(rawPath, subLog);
            } catch (final NullSingletonException e) {
                // Generally thrown on the second and subsequent attempt to call .get(), after the first failed
                throw new IOException("Could not get logical zipfile " + rawPath + " : " + e);
            }
            logicalZipFile = logicalZipFileAndPackageRoot.getKey();
            if (logicalZipFile == null) {
                // Should not happen, but this keeps lgtm static analysis happy
                throw new IOException("Logical zipfile was null");
            }

            // Get the normalized path of the logical zipfile
            zipFilePath = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, logicalZipFile.getPath());

            // Get package root of jarfile
            final String packageRoot = logicalZipFileAndPackageRoot.getValue();
            if (!packageRoot.isEmpty()) {
                packageRootPrefix = packageRoot + "/";
            }
        } catch (final IOException | IllegalArgumentException e) {
            if (subLog != null) {
                subLog.log("Could not open jarfile " + rawPath + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }

        if (!scanSpec.enableSystemJarsAndModules && logicalZipFile.isJREJar) {
            // Found a blacklisted JRE jar that was not caught by filtering for rt.jar in ClasspathFinder
            // (the isJREJar value was set by detecting JRE headers in the jar's manifest file)
            if (subLog != null) {
                subLog.log("Ignoring JRE jar: " + rawPath);
            }
            skipClasspathElement = true;
            return;
        }

        if (!logicalZipFile.isWhitelistedAndNotBlacklisted(scanSpec.jarWhiteBlackList)) {
            if (subLog != null) {
                subLog.log("Skipping jarfile that is blacklisted or not whitelisted: " + rawPath);
            }
            skipClasspathElement = true;
            return;
        }

        // Automatically add any nested "lib/" dirs to classpath, since not all classloaders return them
        // as classpath elements
        int childClasspathEntryIdx = 0;
        if (scanSpec.scanNestedJars) {
            for (final FastZipEntry zipEntry : logicalZipFile.entries) {
                for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                    if (zipEntry.entryNameUnversioned.startsWith(libDirPrefix)
                            && zipEntry.entryNameUnversioned.endsWith(".jar")) {
                        final String entryPath = zipEntry.getPath();
                        if (subLog != null) {
                            subLog.log("Found nested lib jar: " + entryPath);
                        }
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(
                                /* rawClasspathEntry = */ new ClasspathElementAndClassLoader(entryPath,
                                        classLoader),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */
                                childClasspathEntryIdx++));
                        break;
                    }
                }
            }
        }

        // Don't add child classpath elements that are identical to this classpath element, or that are duplicates
        final Set<String> scheduledChildClasspathElements = new HashSet<>();
        scheduledChildClasspathElements.add(rawPath);

        // Create child classpath elements from values obtained from Class-Path entry in manifest, resolving
        // the paths relative to the dir or parent jarfile that the jarfile is contained in
        if (logicalZipFile.classPathManifestEntryValue != null) {
            // Get parent dir of logical zipfile within grandparent slice,
            // e.g. for a zipfile slice path of "/path/to/jar1.jar!/lib/jar2.jar", this is "lib",
            // or for "/path/to/jar1.jar", this is "/path/to", or "" if the jar is in the toplevel dir.
            final String jarParentDir = FileUtils
                    .getParentDirPath(logicalZipFile.getPathWithinParentZipFileSlice());
            // Add paths in manifest file's "Class-Path" entry to the classpath, resolving paths relative to
            // the parent directory or jar
            for (final String childClassPathEltPathRelative : logicalZipFile.classPathManifestEntryValue
                    .split(" ")) {
                if (!childClassPathEltPathRelative.isEmpty()) {
                    // Resolve Class-Path entry relative to containing dir
                    final String childClassPathEltPath = FastPathResolver.resolve(jarParentDir,
                            childClassPathEltPathRelative);
                    // If this is a nested jar, prepend outer jar prefix
                    final ZipFileSlice parentZipFileSlice = logicalZipFile.getParentZipFileSlice();
                    final String childClassPathEltPathWithPrefix = parentZipFileSlice == null
                            ? childClassPathEltPath
                            : parentZipFileSlice.getPath() + (childClassPathEltPath.startsWith("/") ? "!" : "!/")
                                    + childClassPathEltPath;
                    // Only add child classpath elements once
                    if (scheduledChildClasspathElements.add(childClassPathEltPathWithPrefix)) {
                        // Schedule child classpath element for scanning
                        workQueue.addWorkUnit( //
                                new ClasspathEntryWorkUnit(
                                        /* rawClasspathEntry = */ new ClasspathElementAndClassLoader(
                                                childClassPathEltPathWithPrefix, classLoader),
                                        /* parentClasspathElement = */ this,
                                        /* orderWithinParentClasspathElement = */
                                        childClasspathEntryIdx++));
                    }
                }
            }
        }
        // Add paths in an OSGi bundle jar manifest's "Bundle-ClassPath" entry to the classpath, resolving
        // the paths relative to the root of the jarfile
        if (logicalZipFile.bundleClassPathManifestEntryValue != null) {
            final String zipFilePathPrefix = zipFilePath + "!/";
            // Class-Path is split on " ", but Bundle-ClassPath is split on ","
            for (String childBundlePath : logicalZipFile.bundleClassPathManifestEntryValue.split(",")) {
                // Assume that Bundle-ClassPath paths have to be given relative to jarfile root
                while (childBundlePath.startsWith("/")) {
                    childBundlePath = childBundlePath.substring(1);
                }
                // Currently the position of "." relative to child classpath entries is ignored (the
                // Bundle-ClassPath path is treated as if "." is in the first position, since child
                // classpath entries are always added to the classpath after the parent classpath
                // entry that they were obtained from).
                if (!childBundlePath.isEmpty() && !childBundlePath.equals(".")) {
                    // Resolve Bundle-ClassPath entry within jar
                    final String childClassPathEltPath = zipFilePathPrefix
                            + FileUtils.sanitizeEntryPath(childBundlePath, /* removeInitialSlash = */ true);
                    // Only add child classpath elements once
                    if (scheduledChildClasspathElements.add(childClassPathEltPath)) {
                        // Schedule child classpath element for scanning
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(
                                /* rawClasspathEntry = */ //
                                new ClasspathElementAndClassLoader(childClassPathEltPath, classLoader),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */
                                childClasspathEntryIdx++));
                    }
                }
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
            /** The {@link ByteBufferWrapper}, or null. */
            protected ByteBufferWrapper byteBufferWrapper;

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
                return zipEntry.entryName;
            }

            @Override
            public long getLastModified() {
                return zipEntry.getLastModifiedTimeMillis();
            }

            @Override
            public Set<PosixFilePermission> getPosixFilePermissions() {
                final int fileAttributes = zipEntry.fileAttributes;
                Set<PosixFilePermission> perms;
                if (fileAttributes == 0) {
                    perms = null;
                } else {
                    perms = new HashSet<>();
                    if ((fileAttributes & 0400) > 0) {
                        perms.add(PosixFilePermission.OWNER_READ);
                    }
                    if ((fileAttributes & 0200) > 0) {
                        perms.add(PosixFilePermission.OWNER_WRITE);
                    }
                    if ((fileAttributes & 0100) > 0) {
                        perms.add(PosixFilePermission.OWNER_EXECUTE);
                    }
                    if ((fileAttributes & 0040) > 0) {
                        perms.add(PosixFilePermission.GROUP_READ);
                    }
                    if ((fileAttributes & 0020) > 0) {
                        perms.add(PosixFilePermission.GROUP_WRITE);
                    }
                    if ((fileAttributes & 0010) > 0) {
                        perms.add(PosixFilePermission.GROUP_EXECUTE);
                    }
                    if ((fileAttributes & 0004) > 0) {
                        perms.add(PosixFilePermission.OTHERS_READ);
                    }
                    if ((fileAttributes & 0002) > 0) {
                        perms.add(PosixFilePermission.OTHERS_WRITE);
                    }
                    if ((fileAttributes & 0001) > 0) {
                        perms.add(PosixFilePermission.OTHERS_EXECUTE);
                    }
                }
                return perms;
            }

            @Override
            public synchronized InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Jarfile could not be opened");
                }
                markAsOpen();
                try {
                    inputStream = new InputStreamResourceCloser(this, zipEntry.open());
                    length = zipEntry.uncompressedSize;
                    return inputStream;

                } catch (final IOException e) {
                    close();
                    throw e;
                } catch (final InterruptedException e) {
                    close();
                    nestedJarHandler.interruptionChecker.interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            synchronized InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return new InputStreamOrByteBufferAdapter(open());
            }

            @Override
            public synchronized ByteBuffer read() throws IOException {
                try {
                    if (zipEntry.canGetAsSlice()) {
                        try {
                            // For STORED entries that do not span multiple 2GB chunks, can create a
                            // ByteBuffer slice directly from the entry
                            markAsOpen();
                            // compressedSize should have the same value as uncompressedSize for STORED
                            // entries, but compressedSize is more reliable (uncompressedSize may be -1)
                            length = zipEntry.compressedSize;
                            byteBufferWrapper = zipEntry.getAsSlice();
                            byteBuffer = byteBufferWrapper.getByteBuffer();
                            if (byteBuffer == null) {
                                throw new IOException(
                                        "Could not read resource as a ByteBuffer, because memory mapping "
                                                + "of files was disabled, or an OutOfMemoryError occurred while attempting to "
                                                + "map files");
                            }
                            return byteBuffer;

                        } catch (final IOException e) {
                            close();
                            throw e;
                        } catch (final InterruptedException e) {
                            close();
                            nestedJarHandler.interruptionChecker.interrupt();
                            throw new IOException(e);
                        }

                    } else {
                        // Otherwise, decompress or extract the entry into a byte[] array,
                        // then wrap in a ByteBuffer
                        open();
                        return inputStreamToByteBuffer();
                    }
                } catch (final IOException e) {
                    close();
                    throw e;
                } catch (final InterruptedException e) {
                    close();
                    nestedJarHandler.interruptionChecker.interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            public synchronized byte[] load() throws IOException {
                try {
                    open();
                    final byte[] byteArray = inputStreamToByteArray();
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public synchronized void close() {
                super.close(); // Close inputStream
                if (byteBufferWrapper != null) {
                    byteBufferWrapper.close(/* log = */ null);
                    byteBufferWrapper = null;
                    byteBuffer = null;
                }
                if (byteBuffer != null) {
                    byteBuffer = null;
                }
                markAsClosed();
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
    Resource getResource(final String relativePath) {
        return relativePathToResource.get(relativePath);
    }

    /**
     * Scan for path matches within jarfile, and record ZipEntry objects of matching files.
     *
     * @param classpathElementIdx
     *            the index of the classpath element within the classpath or module path.
     * @param log
     *            the log
     */
    @Override
    void scanPaths(final int classpathElementIdx, final LogNode log) {
        if (logicalZipFile == null) {
            skipClasspathElement = true;
        }
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + getZipFilePath());
        }

        final LogNode subLog = log == null ? null
                : log(classpathElementIdx, "Scanning jarfile classpath element " + getZipFilePath(), log);

        Set<String> loggedNestedClasspathRootPrefixes = null;
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            String relativePath = zipEntry.entryNameUnversioned;

            // Paths should never start with "META-INF/versions/{version}/", because either this is a versioned
            // jar, in which case zipEntry.entryNameUnversioned has the version prefix stripped, or this is an
            // unversioned jar (e.g. the multi-version flag is not set in the manifest file) and there are some
            // spurious files in a multi-version path (in which case, they should be ignored).
            if (relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
                if (subLog != null) {
                    if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
                        subLog.log("Skipping versioned entry in jar, because JRE version "
                                + VersionFinder.JAVA_MAJOR_VERSION + " does not support this: " + relativePath);
                    } else {
                        subLog.log(
                                "Found unexpected versioned entry in jar (the jar's manifest file may be missing "
                                        + "the \"Multi-Release\" key) -- skipping: " + relativePath);
                    }
                }
                continue;
            }

            // Check if the relative path is within a nested classpath root
            if (nestedClasspathRootPrefixes != null) {
                // This is O(mn), which is inefficient, but the number of nested classpath roots should be small
                boolean reachedNestedRoot = false;
                for (final String nestedClasspathRoot : nestedClasspathRootPrefixes) {
                    if (relativePath.startsWith(nestedClasspathRoot)) {
                        // relativePath has a prefix of nestedClasspathRoot
                        if (subLog != null) {
                            if (loggedNestedClasspathRootPrefixes == null) {
                                loggedNestedClasspathRootPrefixes = new HashSet<>();
                            }
                            if (loggedNestedClasspathRootPrefixes.add(nestedClasspathRoot)) {
                                subLog.log("Reached nested classpath root, stopping recursion to avoid duplicate "
                                        + "scanning: " + nestedClasspathRoot);
                            }
                        }
                        reachedNestedRoot = true;
                        break;
                    }
                }
                if (reachedNestedRoot) {
                    continue;
                }
            }

            // Ignore entries without the correct classpath root prefix
            if (!packageRootPrefix.isEmpty() && !relativePath.startsWith(packageRootPrefix)) {
                continue;
            }

            // Strip the package root prefix from the relative path
            // N.B. these semantics should mirror those in getResource()
            if (!packageRootPrefix.isEmpty()) {
                relativePath = relativePath.substring(packageRootPrefix.length());
            } else {
                // Strip any package root prefix from the relative path
                for (int i = 0; i < ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES.length; i++) {
                    if (relativePath.startsWith(ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES[i])) {
                        relativePath = relativePath
                                .substring(ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES[i].length());
                    }
                }
            }

            // Whitelist/blacklist classpath elements based on file resource paths
            checkResourcePathWhiteBlackList(relativePath, log);
            if (skipClasspathElement) {
                return;
            }

            // Get match status of the parent directory of this ZipEntry file's relative path (or reuse the last
            // match status for speed, if the directory name hasn't changed).
            final int lastSlashIdx = relativePath.lastIndexOf('/');
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
            final ScanSpecPathMatch parentMatchStatus = //
                    parentRelativePathChanged ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                            : prevParentMatchStatus;
            prevParentRelativePath = parentRelativePath;
            prevParentMatchStatus = parentMatchStatus;

            if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
                // The parent dir or one of its ancestral dirs is blacklisted
                if (subLog != null) {
                    subLog.log("Skipping blacklisted path: " + relativePath);
                }
                continue;
            }

            // Add the ZipEntry path as a Resource
            final Resource resource = newResource(zipEntry, relativePath);
            if (relativePathToResource.putIfAbsent(relativePath, resource) == null) {
                // If resource is whitelisted
                if (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                        || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                && scanSpec.classfileIsSpecificallyWhitelisted(relativePath))) {
                    // Resource is whitelisted
                    addWhitelistedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);
                } else if (scanSpec.enableClassInfo && relativePath.equals("module-info.class")) {
                    // Add module descriptor as a whitelisted classfile resource, so that it is scanned,
                    // but don't add it to the list of resources in the ScanResult, since it is not
                    // in a whitelisted package (#352)
                    addWhitelistedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                }
            }
        }

        // Save the last modified time for the zipfile
        final File zipfile = getFile();
        if (zipfile != null) {
            fileToLastModified.put(zipfile, zipfile.lastModified());
        }

        finishScanPaths(subLog);
    }

    /**
     * Get module name from module descriptor, or get the automatic module name from the manifest file, or derive an
     * automatic module name from the jar name.
     *
     * @return the module name
     */
    @Override
    public String getModuleName() {
        String moduleName = moduleNameFromModuleDescriptor;
        if (moduleName == null || moduleName.isEmpty()) {
            moduleName = moduleNameFromManifestFile;
        }
        if (moduleName == null || moduleName.isEmpty()) {
            if (derivedAutomaticModuleName == null) {
                derivedAutomaticModuleName = JarUtils.derivedAutomaticModuleName(zipFilePath);
            }
            moduleName = derivedAutomaticModuleName;
        }
        return moduleName == null || moduleName.isEmpty() ? null : moduleName;
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

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        try {
            return new URI(URLPathEncoder.normalizeURLPath(getZipFilePath()));
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Could not form URI: " + e);
        }
    }

    /**
     * Get the {@link File} for the outermost zipfile of this classpath element.
     *
     * @return The {@link File} for the outermost zipfile of this classpath element, or null if this file was
     *         downloaded from a URL directly to RAM.
     */
    @Override
    File getFile() {
        if (logicalZipFile != null) {
            return logicalZipFile.getPhysicalFile();
        } else {
            // Not performing a full scan (only getting classpath elements), so logicalZipFile is not set
            final int plingIdx = rawPath.indexOf('!');
            final String outermostZipFilePathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
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

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClasspathElementZip)) {
            return false;
        }
        final ClasspathElementZip other = (ClasspathElementZip) obj;
        return this.getZipFilePath().equals(other.getZipFilePath());
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return getZipFilePath().hashCode();
    }
}
