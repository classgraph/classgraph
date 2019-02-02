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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.Scanner.RawClasspathElementWorkUnit;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.FastZipEntry;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    /** The raw path for this zipfile. */
    private final String rawPath;
    /** The logical zipfile for this classpath element. */
    private LogicalZipFile logicalZipFile;
    /** The package root within the jarfile. */
    private String packageRootPrefix = "";
    /** The normalized path of the jarfile, "!/"-separated if nested, excluding any package root. */
    private String zipFilePath;
    /** A map from relative path to {@link Resource} for non-blacklisted zip entries. */
    private final Map<String, Resource> relativePathToResource = new HashMap<>();
    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    ClasspathElementZip(final String rawPath, final ClassLoader[] classLoaders,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(classLoaders, scanSpec);
        this.rawPath = rawPath;
        this.zipFilePath = rawPath;
        this.nestedJarHandler = nestedJarHandler;
        if (scanSpec.performScan) {
            whitelistedResources = new ArrayList<>();
            whitelistedClassfileResources = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    @Override
    void open(final WorkQueue<RawClasspathElementWorkUnit> workQueue, final LogNode log) {
        if (!scanSpec.scanJars) {
            if (log != null) {
                log.log("Skipping classpath element, since jar scanning is disabled: " + rawPath);
            }
            skipClasspathElement = true;
            return;
        }
        final LogNode subLog = log == null ? null : log.log("Opening jar: " + rawPath);
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
        if (scanSpec.performScan) {
            // If performing the scan, get logical zipfile hierarchy
            try {
                // Get LogicalZipFile for innermost nested jarfile
                final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = //
                        nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap.get(rawPath, subLog);
                logicalZipFile = logicalZipFileAndPackageRoot.getKey();

                // Get the normalized path of the logical zipfile
                zipFilePath = logicalZipFile.getPath();

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
            } catch (final Exception e) {
                if (subLog != null) {
                    subLog.log("Exception while opening jarfile " + rawPath, e);
                }
                skipClasspathElement = true;
                return;
            }
        } else {
            // Only getting classpath elements
            zipFilePath = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, rawPath);
        }

        if (logicalZipFile != null) {
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
                            workQueue.addWorkUnit(new RawClasspathElementWorkUnit(
                                    /* rawClasspathEltPath = */ entryPath, /* parentClasspathElement = */ this,
                                    /* orderWithinParentClasspathElement = */
                                    childClasspathEntryIdx++));
                            break;
                        }
                    }
                }
            }

            // Create child classpath elements from values obtained from Class-Path entry in manifest
            if (logicalZipFile.classPathManifestEntryValue != null) {
                // Class-Path entries in the manifest file are resolved relative to the dir that
                // the manifest's jarfile is contained in -- get parent dir of outermost zipfile
                final String pathOfContainingDir = FastPathResolver
                        .resolve(logicalZipFile.physicalZipFile.getFile().getParent());
                for (final String childClassPathEltPath : logicalZipFile.classPathManifestEntryValue.split(" ")) {
                    if (!childClassPathEltPath.isEmpty()) {
                        // Resolve Class-Path entry relative to containing dir
                        final String childClassPathEltPathResolved = FastPathResolver.resolve(pathOfContainingDir,
                                childClassPathEltPath);
                        // Only add child classpath elements once
                        if (!childClassPathEltPathResolved.equals(rawPath)) {
                            // Schedule child classpath element for scanning
                            workQueue.addWorkUnit(new RawClasspathElementWorkUnit(
                                    /* rawClasspathEltPath = */ childClassPathEltPathResolved,
                                    /* parentClasspathElement = */ this,
                                    /* orderWithinParentClasspathElement = */
                                    childClasspathEntryIdx++));
                        }
                    }
                }
            }
        }
    }

    /** Create a new {@link Resource} object for a resource or classfile discovered while scanning paths. */
    private Resource newResource(final FastZipEntry zipEntry, final String pathRelativeToPackageRoot) {
        return new Resource() {
            {
                // ZipEntry size may be unknown (-1L), or even completely wrong
                length = zipEntry.uncompressedSize;
            }

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
            public URL getURL() {
                try {
                    return URLPathEncoder.urlPathToURL(zipFilePath + "!/" + zipEntry.entryName);
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for resource: " + e);
                }
            }

            @Override
            public URL getClasspathElementURL() {
                return getZipFileURL();
            }

            @Override
            public File getClasspathElementFile() {
                return getZipFile();
            }

            @Override
            public ModuleRef getModuleRef() {
                return null;
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

                } catch (final Exception e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            synchronized InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return new InputStreamOrByteBufferAdapter(open());
            }

            @Override
            public synchronized ByteBuffer read() throws IOException {
                if (zipEntry.canGetAsSlice()) {
                    // For STORED entries that do not span multiple 2GB chunks, can create a
                    // ByteBuffer slice directly from the entry
                    markAsOpen();
                    try {
                        // compressedSize should have the same value as uncompressedSize for STORED
                        // entries, but compressedSize is more reliable (uncompressedSize may be -1)
                        length = zipEntry.compressedSize;
                        return zipEntry.getAsSlice();

                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                } else {
                    // Otherwise, decompress or extract the entry into a byte[] array,
                    // then wrap in a ByteBuffer
                    open();
                    return inputStreamToByteBuffer();
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
                if (byteBuffer != null) {
                    byteBuffer = null;
                }
                markAsClosed();
            }
        };
    }

    /**
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String relativePath) {
        return relativePathToResource.get(relativePath);
    }

    /** Scan for path matches within jarfile, and record ZipEntry objects of matching files. */
    @Override
    void scanPaths(final LogNode log) {
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
                : log.log(getZipFilePath(), "Scanning jarfile classpath element " + getZipFilePath());

        Set<String> loggedNestedClasspathRootPrefixes = null;
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final FastZipEntry zipEntry : logicalZipFile.entries) {
            String relativePath = zipEntry.entryNameUnversioned;

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
            if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistAndBlacklistAreEmpty()) {
                if (scanSpec.classpathElementResourcePathWhiteBlackList.isBlacklisted(relativePath)) {
                    if (subLog != null) {
                        subLog.log("Reached blacklisted classpath element resource path, stopping scanning: "
                                + relativePath);
                    }
                    skipClasspathElement = true;
                    return;
                }
                if (scanSpec.classpathElementResourcePathWhiteBlackList.isSpecificallyWhitelisted(relativePath)) {
                    if (subLog != null) {
                        subLog.log("Reached specifically whitelisted classpath element resource path: "
                                + relativePath);
                    }
                    containsSpecificallyWhitelistedClasspathElementResourcePath = true;
                }
            }

            // Get match status of the parent directory of this ZipEntry file's relative path (or reuse the last
            // match status for speed, if the directory name hasn't changed).
            final int lastSlashIdx = relativePath.lastIndexOf("/");
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
            if (!relativePathToResource.containsKey(relativePath)) {
                relativePathToResource.put(relativePath, resource);
            }
            if (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                    || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                    || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                            && scanSpec.classfileIsSpecificallyWhitelisted(relativePath))) {
                // Resource is whitelisted
                addWhitelistedResource(resource, parentMatchStatus, subLog);
            }
        }

        if (subLog != null) {
            if (whitelistedResources.isEmpty() && whitelistedClassfileResources.isEmpty()) {
                subLog.log("No whitelisted classfiles or resources found");
            } else if (whitelistedResources.isEmpty()) {
                subLog.log("No whitelisted resources found");
            } else if (whitelistedClassfileResources.isEmpty()) {
                subLog.log("No whitelisted classfiles found");
            }
        }

        // Save the last modified time for the zipfile
        fileToLastModified.put(getZipFile(), getZipFile().lastModified());

        if (subLog != null) {
            subLog.addElapsedTime();
        }
    }

    @Override
    String getPackageRoot() {
        return packageRootPrefix;
    }

    /** @return The {@link File} for the outermost zipfile of this classpath element. */
    public File getZipFile() {
        if (logicalZipFile != null) {
            return logicalZipFile.physicalZipFile.getFile();
        } else {
            // Not performing a full scan (only getting classpath elements), so logicalZipFile is not set
            final int plingIdx = rawPath.indexOf('!');
            final String outermostZipFilePathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                    plingIdx < 0 ? rawPath : rawPath.substring(0, plingIdx));
            final File outermostZipFile = new File(outermostZipFilePathResolved);
            return outermostZipFile;
        }
    }

    /** @return the path of the zipfile, including any package root. */
    public String getZipFilePath() {
        return packageRootPrefix.isEmpty() ? zipFilePath
                : zipFilePath + "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
    }

    /**
     * @return the URL for a jarfile, with "!/" separating any nested jars, optionally followed by "!/" and then a
     *         package root.
     */
    public URL getZipFileURL() {
        try {
            return URLPathEncoder.urlPathToURL(getZipFilePath());
        } catch (final MalformedURLException e) {
            // Shouldn't happen
            throw new IllegalArgumentException("Could not form URL for classpath element: " + e);
        }
    }

    /** Return the classpath element's path. */
    @Override
    public String toString() {
        return getZipFilePath();
    }
}
