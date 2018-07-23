/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner;

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

import io.github.lukehutch.fastclasspathscanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathOrModulePathEntry;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathUtils;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.InputStreamOrByteBufferAdapter;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.JarfileMetadataReader;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    private File classpathEltZipFile;
    /** Result of parsing the manifest file for this jarfile. */
    private JarfileMetadataReader jarfileMetadataReader;
    /** The package root within the jarfile. */
    private String packageRootPrefix;

    private Recycler<ZipFile, IOException> zipFileRecycler;

    /** A zip/jarfile classpath element. */
    ClasspathElementZip(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec,
            final boolean scanFiles, final NestedJarHandler nestedJarHandler,
            final WorkQueue<ClasspathOrModulePathEntry> workQueue, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles, interruptionChecker);
        try {
            classpathEltZipFile = classpathEltPath.getFile(log);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
            }
            skipClasspathElement = true;
            return;
        }
        if (classpathEltZipFile == null || !ClasspathUtils.canRead(classpathEltZipFile)) {
            if (log != null) {
                log.log("Skipping non-existent jarfile " + classpathEltPath.getResolvedPath());
            }
            skipClasspathElement = true;
            return;
        }
        try {
            zipFileRecycler = nestedJarHandler.getZipFileRecycler(classpathEltZipFile, log);
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while creating zipfile recycler for " + classpathEltZipFile + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }

        final String packageRoot = getJarfilePackageRoot();
        try {
            jarfileMetadataReader = nestedJarHandler.getJarfileMetadataReader(classpathEltZipFile, packageRoot,
                    log);
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while reading metadata from " + classpathEltZipFile + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }
        if (!packageRoot.isEmpty()) {
            if (log != null) {
                log.log("Package root within jarfile: " + packageRoot);
            }
            packageRootPrefix = packageRoot + "/";
        } else {
            packageRootPrefix = "";
        }
        while (packageRootPrefix.startsWith("/")) {
            // Strip any initial "/" to correspond with handling of relativePath below
            packageRootPrefix = packageRootPrefix.substring(1);
        }

        ZipFile zipFile = null;
        try {
            zipFile = zipFileRecycler.acquire();
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception opening zipfile " + classpathEltZipFile + " : " + e.getMessage());
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Parse the manifest entry if present
            if (jarfileMetadataReader != null && jarfileMetadataReader.classPathEntriesToScan != null) {
                final LogNode childClasspathLog = log == null ? null
                        : log.log("Found additional classpath entries in metadata for " + classpathEltZipFile);

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
                        if (childClasspathLog != null) {
                            childClasspathLog.log(childRelativePath.toString());
                        }
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
            if (scanFiles) {
                fileMatches = new ArrayList<>();
                classfileMatches = new ArrayList<>();
                fileToLastModified = new HashMap<>();
            }
        } finally {
            if (zipFile != null) {
                zipFileRecycler.release(zipFile);
                zipFile = null;
            }
        }
    }

    private Resource newClasspathResource(final File jarFile, final String packageRootPrefix,
            final String pathRelativeToPackageRoot, final ZipEntry zipEntry) {
        return new Resource() {
            private ZipFile zipFile;
            private String pathRelativeToClasspathElt = null;

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
                    return new URL(jarFile.toURI().toURL().toString() + "!" + getPathRelativeToClasspathElement());
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for jarfile: " + jarFile + " ; path: "
                            + pathRelativeToClasspathElt);
                }
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Jarfile could not be opened");
                }
                if (inputStream != null) {
                    return inputStream;
                } else {
                    try {
                        zipFile = zipFileRecycler.acquire();
                        inputStream = zipFile.getInputStream(zipEntry);
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
                read();
                final byte[] byteArray = inputStreamToByteArray();
                close();
                return byteArray;
            }

            @Override
            public void close() {
                super.close();
                if (zipFile != null) {
                    zipFileRecycler.release(zipFile);
                    zipFile = null;
                }
            }

            @Override
            protected String toStringImpl() {
                return "[jar " + jarFile + "]/" + getPathRelativeToClasspathElement();
            }
        };
    }

    /** Scan for path matches within jarfile, and record ZipEntry objects of matching files. */
    @Override
    public void scanPaths(final LogNode log) {
        final String path = classpathEltPath.getResolvedPath();
        String canonicalPath = path;
        try {
            canonicalPath = classpathEltPath.getCanonicalPath(log);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception canonicalizing path " + classpathEltPath + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }
        final LogNode subLog = log == null ? null
                : log.log(canonicalPath, "Scanning jarfile classpath entry " + classpathEltPath
                        + (path.equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));
        ZipFile zipFile = null;
        try {
            zipFile = zipFileRecycler.acquire();
        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening zipfile " + classpathEltZipFile + " : " + e);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Support specification of a classpath root within a jarfile, e.g. "spring-project.jar!/BOOT-INF/classes"
            final int requiredPrefixLen = packageRootPrefix.length();

            Set<String> loggedNestedClasspathRootPrefixes = null;
            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final ZipEntry zipEntry : jarfileMetadataReader.zipEntries) {
                // Normalize path of ZipEntry
                String relativePath = zipEntry.getName();
                while (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }

                // Ignore entries without the correct classpath root prefix
                if (requiredPrefixLen > 0) {
                    if (!relativePath.startsWith(packageRootPrefix)) {
                        continue;
                    }
                    // Strip the classpath root prefix from the relative path
                    relativePath = relativePath.substring(requiredPrefixLen);
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
                                    subLog.log(
                                            "Reached nested classpath root, stopping recursion to avoid duplicate "
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

                // Get match status of the parent directory of this zipentry file's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf("/");
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = prevParentRelativePath == null
                        || !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        parentRelativePathChanged ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile that has
                // been specifically-whitelisted
                if (parentMatchStatus != ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        && parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_PATH
                        && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                    if (subLog != null) {
                        subLog.log("Skipping non-whitelisted path: " + relativePath);
                    }
                    continue;
                }

                if (subLog != null) {
                    subLog.log(relativePath, "Found whitelisted file: " + relativePath);
                }

                if (scanSpec.enableClassInfo) {
                    // Store relative paths of any classfiles encountered
                    if (FileUtils.isClassfile(relativePath)) {
                        classfileMatches.add(newClasspathResource(classpathEltZipFile, packageRootPrefix,
                                relativePath, zipEntry));
                    }
                }

                // Record all classpath resources found in whitelisted paths
                fileMatches
                        .add(newClasspathResource(classpathEltZipFile, packageRootPrefix, relativePath, zipEntry));
            }
            // Don't use the last modified time from the individual zipEntry
            // objects, we use the last modified time for the zipfile itself instead.
            fileToLastModified.put(classpathEltZipFile, classpathEltZipFile.lastModified());

        } finally {
            if (zipFile != null) {
                zipFileRecycler.release(zipFile);
                zipFile = null;
            }
        }
        if (subLog != null) {
            subLog.addElapsedTime();
        }
    }

    /** Close and free all open ZipFiles. */
    @Override
    public void close() {
        if (zipFileRecycler != null) {
            zipFileRecycler.close();
        }
        zipFileRecycler = null;
    }
}
