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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.utils.ClasspathOrModulePathEntry;
import io.github.classgraph.utils.FastPathResolver;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import io.github.classgraph.utils.JarfileMetadataReader;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.NestedJarHandler;
import io.github.classgraph.utils.Recycler;
import io.github.classgraph.utils.URLPathEncoder;
import io.github.classgraph.utils.VersionedZipEntry;
import io.github.classgraph.utils.WorkQueue;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    private File classpathEltZipFile;
    /** Result of parsing the manifest file for this jarfile. */
    private JarfileMetadataReader jarfileMetadataReader;
    /** The package root within the jarfile. */
    private String packageRootPrefix = "";
    /** The ZipFile recycler. */
    private Recycler<ZipFile, IOException> zipFileRecycler;

    /** A zip/jarfile classpath element. */
    ClasspathElementZip(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec,
            final NestedJarHandler nestedJarHandler, final WorkQueue<ClasspathOrModulePathEntry> workQueue,
            final LogNode log) {
        super(classpathEltPath, scanSpec);
        try {
            classpathEltZipFile = classpathEltPath.getFile(log);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
            }
            skipClasspathElement = true;
            return;
        }
        if (classpathEltZipFile == null || !FileUtils.canRead(classpathEltZipFile)) {
            if (log != null) {
                log.log("Skipping non-existent jarfile " + classpathEltPath.getResolvedPath());
            }
            skipClasspathElement = true;
            return;
        }
        try {
            classpathEltPath.getCanonicalPath(log);
        } catch (final Exception e) {
            if (log != null) {
                log.log("Skipping jarfile " + classpathEltPath.getResolvedPath()
                        + " -- could not canonicalize path : " + e);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            zipFileRecycler = nestedJarHandler.getZipFileRecycler(classpathEltZipFile, log);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while creating zipfile recycler for " + classpathEltZipFile + " : " + e);
            }
            skipClasspathElement = true;
            return;
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while creating zipfile recycler for " + classpathEltZipFile, e);
            }
            skipClasspathElement = true;
            return;
        }

        try {
            jarfileMetadataReader = nestedJarHandler.getJarfileMetadataReader(classpathEltZipFile, log);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while reading metadata from " + classpathEltZipFile + " : " + e);
            }
            skipClasspathElement = true;
            return;
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while reading metadata from " + classpathEltZipFile, e);
            }
            skipClasspathElement = true;
            return;
        }
        if (jarfileMetadataReader == null) {
            skipClasspathElement = true;
            return;
        }

        String packageRoot = classpathEltPath.getJarfilePackageRoot();
        while (packageRoot.startsWith("/")) {
            // Strip any initial "/" to correspond with handling of relativePath below
            packageRoot = packageRoot.substring(1);
        }
        if (!packageRoot.isEmpty() && !packageRoot.endsWith("/")) {
            packageRoot += "/";
        }
        if (!packageRoot.isEmpty()) {
            // Don't use package root if it has already been stripped from paths (e.g. if the jarfile URL was
            // "/path/to/spring-boot.jar!BOOT-INF/classes" -- the "BOOT-INF/classes/" prefix was already stripped
            // from zipfile entries)
            if (!jarfileMetadataReader.strippedPathPrefixes.contains(packageRoot)) {
                if (log != null) {
                    log.log("Package root within jarfile: " + packageRoot);
                }
                packageRootPrefix = packageRoot;
            }
        }

        // Parse the manifest entry if present
        if (jarfileMetadataReader.classPathEntriesToScan != null) {
            // Class-Path entries in the manifest file are resolved relative to the dir the manifest's jarfile
            // is contaiin. Get the parent path.
            final String pathOfContainingDir = FastPathResolver.resolve(classpathEltZipFile.getParent());

            // Create child classpath elements from Class-Path entry
            if (childClasspathElts == null) {
                childClasspathElts = new ArrayList<>(jarfileMetadataReader.classPathEntriesToScan.size());
            }
            for (int i = 0; i < jarfileMetadataReader.classPathEntriesToScan.size(); i++) {
                final String childClassPathEltPath = jarfileMetadataReader.classPathEntriesToScan.get(i);
                final ClasspathOrModulePathEntry childRelativePath = new ClasspathOrModulePathEntry(
                        pathOfContainingDir, childClassPathEltPath, classpathEltPath.getClassLoaders(),
                        nestedJarHandler, scanSpec, log);
                if (!childRelativePath.equals(classpathEltPath)) {
                    // Add child classpath element. This may add lib jars more than once, in the case of a
                    // jar with "BOOT-INF/classes" and "BOOT-INF/lib", since this method may be called initially
                    // with "" as the package root, and then a second time with "BOOT-INF/classes" as a package
                    // root, and both times it will find "BOOT-INF/lib" -- but the caller will deduplicate
                    // the multiply-added lib jars.
                    childClasspathElts.add(childRelativePath);
                }
            }

            // Schedule child classpath elements for scanning
            if (!childClasspathElts.isEmpty()) {
                if (workQueue != null) {
                    workQueue.addWorkUnits(childClasspathElts);
                } else {
                    // When adding rt.jar, workQueue will be null. But rt.jar should not include Class-Path
                    // references (so this block should not be reached).
                    if (log != null) {
                        log.log("Ignoring Class-Path entries in rt.jar: " + childClasspathElts);
                    }
                }
            }
        }
        if (scanSpec.performScan) {
            resourceMatches = new ArrayList<>();
            whitelistedClassfileResources = new ArrayList<>();
            nonBlacklistedClassfileResources = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    @Override
    String getJarfilePackageRoot() {
        return packageRootPrefix;
    }

    /** Create a new {@link Resource} object for a resource or classfile discovered while scanning paths. */
    private Resource newResource(final File jarFile, final String packageRootPrefix,
            final String pathRelativeToPackageRoot, final ZipEntry zipEntry) {
        return new Resource() {
            private Recycler<ZipFile, IOException>.Recyclable zipFileRecyclable;
            private ZipFile zipFile;
            private String pathRelativeToClasspathElt = null;

            {
                // ZipEntry size may be unknown (-1L), or even completely wrong
                length = zipEntry.getSize();
            }

            @Override
            public String getPath() {
                return pathRelativeToPackageRoot;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return pathRelativeToClasspathElt == null
                        ? pathRelativeToClasspathElt = packageRootPrefix + pathRelativeToPackageRoot
                        : pathRelativeToClasspathElt;
            }

            @Override
            public URL getURL() {
                try {
                    return new URL("jar:" + jarFile.toURI().toURL().toString() + "!/"
                            + URLPathEncoder.encodePath(getPathRelativeToClasspathElement()));
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for jarfile: " + jarFile + " ; path: "
                            + pathRelativeToClasspathElt);
                }
            }

            @Override
            public URL getClasspathElementURL() {
                try {
                    return packageRootPrefix.isEmpty() ? getClasspathElementFile().toURI().toURL()
                            : new URL("jar:" + getClasspathElementFile().toURI().toURL() + "!/"
                                    + URLPathEncoder.encodePath(packageRootPrefix));
                } catch (final MalformedURLException e) {
                    // Shouldn't happen
                    throw new IllegalArgumentException(e);
                }
            }

            @Override
            public File getClasspathElementFile() {
                return classpathEltZipFile;
            }

            @Override
            public ModuleRef getModuleRef() {
                return null;
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Jarfile could not be opened");
                }
                if (inputStream != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        zipFileRecyclable = zipFileRecycler.acquire();
                        zipFile = zipFileRecyclable.get();
                        inputStream = new InputStreamResourceCloser(this, zipFile.getInputStream(zipEntry));
                        length = zipEntry.getSize();
                        return inputStream;
                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return InputStreamOrByteBufferAdapter.create(open());
            }

            @Override
            public ByteBuffer read() throws IOException {
                open();
                return inputStreamToByteBuffer();
            }

            @Override
            public byte[] load() throws IOException {
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
            public void close() {
                if (inputStream != null) {
                    try {
                        // Avoid infinite loop with InputStreamResourceCloser trying to close its parent resource
                        final InputStream inputStreamWrapper = inputStream;
                        inputStream = null;
                        inputStreamWrapper.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
                if (byteBuffer != null) {
                    byteBuffer = null;
                }
                if (zipFile != null) {
                    // Leave the ZipFile open in the recycler, just set the ref to null here.
                    // The open ZipFile instances are closed when ClasspathElementZip#close() is called.
                    zipFile = null;
                }
                if (zipFileRecyclable != null) {
                    // Recycle the (open) ZipFile
                    zipFileRecyclable.close();
                    zipFileRecyclable = null;
                }
            }
        };
    }

    /** Scan for path matches within jarfile, and record ZipEntry objects of matching files. */
    @Override
    void scanPaths(final LogNode log) {
        if (jarfileMetadataReader == null) {
            skipClasspathElement = true;
        }
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + toString());
        }

        final LogNode subLog = log == null ? null
                : log.log(classpathEltPath.getResolvedPath(),
                        "Scanning jarfile classpath element " + classpathEltPath);

        Set<String> loggedNestedClasspathRootPrefixes = null;
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final VersionedZipEntry versionedZipEntry : jarfileMetadataReader.versionedZipEntries) {
            String relativePath = versionedZipEntry.unversionedPath;

            // Ignore entries without the correct classpath root prefix
            if (!(packageRootPrefix.length() == 0 || relativePath.startsWith(packageRootPrefix))) {
                continue;
            }

            // Strip the package root prefix from the relative path
            relativePath = relativePath.substring(packageRootPrefix.length());

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

            // Get match status of the parent directory of this ZipEntry file's relative path (or reuse the last
            // match status for speed, if the directory name hasn't changed).
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final boolean parentRelativePathChanged = prevParentRelativePath == null
                    || !parentRelativePath.equals(prevParentRelativePath);
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
            final Resource resource = newResource(classpathEltZipFile, packageRootPrefix, relativePath,
                    versionedZipEntry.zipEntry);
            addResource(resource, parentMatchStatus, subLog);
        }

        // Save the last modified time for the zipfile
        fileToLastModified.put(classpathEltZipFile, classpathEltZipFile.lastModified());
    }

    /** Close and free all open ZipFiles. */
    @Override
    void closeRecyclers() {
        if (zipFileRecycler != null) {
            // Close the open ZipFile instances for this classpath element.
            zipFileRecycler.close();
        }
    }
}
