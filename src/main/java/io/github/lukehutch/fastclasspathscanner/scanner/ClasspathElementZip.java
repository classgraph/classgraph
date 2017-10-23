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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathUtils;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    private File classpathEltZipFile;
    /** Result of parsing the manifest file for this jarfile. */
    private FastManifestParser fastManifestParser;
    private Recycler<ZipFile, IOException> zipFileRecycler;

    /** A zip/jarfile classpath element. */
    ClasspathElementZip(final RelativePath classpathEltPath, final ScanSpec scanSpec, final boolean scanFiles,
            final NestedJarHandler nestedJarHandler, final WorkQueue<RelativePath> workQueue,
            final InterruptionChecker interruptionChecker, final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles, interruptionChecker);
        try {
            classpathEltZipFile = classpathEltPath.getFile();
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
            }
            ioExceptionOnOpen = true;
            return;
        }
        if (classpathEltZipFile == null || !ClasspathUtils.canRead(classpathEltZipFile)) {
            if (log != null) {
                log.log("Skipping non-existent jarfile " + classpathEltPath.getResolvedPath());
            }
            ioExceptionOnOpen = true;
            return;
        }
        try {
            zipFileRecycler = nestedJarHandler.getZipFileRecycler(classpathEltZipFile.getPath());
        } catch (final Exception e) {
            // Stop other threads
            interruptionChecker.interrupt();
            if (log != null) {
                log.log("Exception while creating zipfile recycler", e);
            }
            ioExceptionOnOpen = true;
            return;
        }

        ZipFile zipFile = null;
        try {
            try {
                zipFile = zipFileRecycler.acquire();
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Exception opening zipfile " + classpathEltZipFile, e);
                }
                ioExceptionOnOpen = true;
                return;
            }
            // If not performing a scan, get the manifest entry if present
            fastManifestParser = new FastManifestParser(zipFile, log);
            if (fastManifestParser != null && fastManifestParser.classPath != null) {
                final LogNode manifestLog = log == null ? null
                        : log.log("Manifest file " + FastManifestParser.MANIFEST_PATH + " has Class-Path entries");

                // Get the classpath elements from the Class-Path manifest entry
                // (these are space-delimited).
                childClasspathElts = new ArrayList<>(fastManifestParser.classPath.size());

                // Class-Path entries in the manifest file are resolved relative to
                // the dir the manifest's jarfile is contaiin. Get the parent path.
                final String pathOfContainingDir = FastPathResolver.resolve(classpathEltZipFile.getParent());

                // Create child classpath elements from Class-Path entry
                for (int i = 0; i < fastManifestParser.classPath.size(); i++) {
                    final String manifestClassPathEltPath = fastManifestParser.classPath.get(i);
                    final RelativePath childRelativePath = new RelativePath(pathOfContainingDir,
                            manifestClassPathEltPath, classpathEltPath.getClassLoaders(), nestedJarHandler);
                    childClasspathElts.add(childRelativePath);
                    if (manifestLog != null) {
                        manifestLog.log("Found Class-Path entry in manifest: " + manifestClassPathEltPath + " -> "
                                + childRelativePath);
                    }
                }

                // Schedule child classpath elements for scanning
                if (!childClasspathElts.isEmpty()) {
                    if (workQueue != null) {
                        workQueue.addWorkUnits(childClasspathElts);
                    } else {
                        // When adding rt.jar, workQueue will be null. But rt.jar should not include
                        // Class-Path references (so this block should not be reached).
                        if (log != null) {
                            log.log("Ignoring Class-Path entries in rt.jar: " + childClasspathElts);
                        }
                    }
                }
            }
            if (scanFiles) {
                fileMatches = new MultiMapKeyToList<>();
                classfileMatches = new ArrayList<>();
                fileToLastModified = new HashMap<>();
            }
        } finally {
            zipFileRecycler.release(zipFile);
        }
    }

    /** Scan for path matches within jarfile, and record ZipEntry objects of matching files. */
    @Override
    public void scanPaths(final LogNode log) {
        ZipFile zipFile = null;
        try {
            try {
                zipFile = zipFileRecycler.acquire();
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Exception opening zipfile " + classpathEltZipFile, e);
                }
                ioExceptionOnOpen = true;
                return;
            }
            scanZipFile(classpathEltZipFile, zipFile, classpathEltPath.getZipClasspathBaseDir(), log);
        } finally {
            zipFileRecycler.release(zipFile);
        }
    }

    private ClasspathResource newClasspathResource(final File classpathEltFile,
            final String pathRelativeToClasspathElt, final String pathRelativeToClasspathPrefix,
            final ZipEntry zipEntry) {
        return new ClasspathResource(classpathEltFile, pathRelativeToClasspathElt, pathRelativeToClasspathPrefix) {
            ZipFile zipFile = null;
            InputStream inputStream = null;

            @Override
            public InputStream open() throws IOException {
                if (ioExceptionOnOpen) {
                    // Can't open a file inside a zipfile if the zipfile couldn't be opened
                    // (should never be triggered)
                    throw new IOException("Parent zipfile could not be opened");
                }
                try {
                    if (zipFile != null || inputStream != null) {
                        // Should not happen, since this will only be called from single-threaded code
                        // when MatchProcessors are running
                        throw new RuntimeException("Tried to open classpath resource twice");
                    }
                    zipFile = zipFileRecycler.acquire();
                    inputStream = zipFile.getInputStream(zipEntry);
                    inputStreamLength = zipEntry.getSize();
                    return inputStream;
                } catch (final Exception e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            public void close() {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (final Exception e) {
                        // Ignore
                    }
                    inputStream = null;
                }
                if (zipFile != null) {
                    zipFileRecycler.release(zipFile);
                    zipFile = null;
                }
            }
        };
    }

    /** Scan a zipfile for file path patterns matching the scan spec. */
    private void scanZipFile(final File zipFileFile, final ZipFile zipFile, final String classpathBaseDir,
            final LogNode log) {
        // Support specification of a classpath root within a jarfile, as required by Spring,
        // e.g. "spring-project.jar!/BOOT-INF/classes"
        String requiredPrefix;
        if (!classpathBaseDir.isEmpty()) {
            if (log != null) {
                log.log("Classpath prefix within jarfile: " + classpathBaseDir);
            }
            requiredPrefix = classpathBaseDir + "/";
        } else {
            requiredPrefix = "";
        }
        final int requiredPrefixLen = requiredPrefix.length();

        // Convert set to list for faster iteration
        List<String> nestedClasspathRootsList = null;
        if (nestedClasspathRoots != null) {
            nestedClasspathRootsList = new ArrayList<>(nestedClasspathRoots);
        }

        Set<String> loggedNestedClasspathRoots = null;
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        int entryIdx = 0;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Check for interruption every 1024 entries
            if ((entryIdx++ & 0x3ff) == 0) {
                if (interruptionChecker.checkAndReturn()) {
                    return;
                }
            }

            // Get next ZipEntry
            final ZipEntry zipEntry = entries.nextElement();

            // Ignore directory entries, they are not used
            final boolean isDir = zipEntry.isDirectory();
            if (isDir) {
                continue;
            }

            // Normalize path of ZipEntry
            String relativePath = zipEntry.getName();
            if (relativePath.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                relativePath = relativePath.substring(1);
            }

            // Ignore entries without the correct classpath root prefix
            if (requiredPrefixLen > 0) {
                if (!relativePath.startsWith(requiredPrefix)) {
                    continue;
                }
                // Strip the classpath root prefix from the relative path
                relativePath = relativePath.substring(requiredPrefixLen);
            }

            // Get match status of the parent directory of this zipentry file's relative path
            // (or reuse the last match status for speed, if the directory name hasn't changed). 
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
            final ScanSpecPathMatch parentMatchStatus = // 
                    prevParentRelativePath == null || parentRelativePathChanged
                            ? scanSpec.pathWhitelistMatchStatus(parentRelativePath)
                            : prevParentMatchStatus;
            prevParentRelativePath = parentRelativePath;
            prevParentMatchStatus = parentMatchStatus;

            // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
            // that has been specifically-whitelisted
            if (parentMatchStatus != ScanSpecPathMatch.WITHIN_WHITELISTED_PATH
                    && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                            || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                continue;
            }

            // Check if the relative path is within a nested classpath root
            if (nestedClasspathRootsList != null) {
                // This is O(mn), which is inefficient, but the number of nested classpath roots should be small
                for (final String nestedClasspathRoot : nestedClasspathRootsList) {
                    if (relativePath.startsWith(nestedClasspathRoot)) {
                        // relativePath has a prefix of nestedClasspathRoot
                        if (log != null) {
                            if (loggedNestedClasspathRoots == null) {
                                loggedNestedClasspathRoots = new HashSet<>();
                            }
                            if (loggedNestedClasspathRoots.add(nestedClasspathRoot)) {
                                log.log("Reached nested classpath root, stopping recursion to avoid duplicate "
                                        + "scanning: " + nestedClasspathRoot);
                            }
                        }
                        continue;
                    }
                }
            }

            if (log != null) {
                log.log("Found whitelisted file: " + relativePath);
            }

            // Store relative paths of any classfiles encountered
            if (FileUtils.isClassfile(relativePath)) {
                classfileMatches.add(
                        newClasspathResource(zipFileFile, requiredPrefix + relativePath, relativePath, zipEntry));
            }

            // Match file paths against path patterns
            for (final FileMatchProcessorWrapper fileMatchProcessorWrapper : //
            scanSpec.getFileMatchProcessorWrappers()) {
                if (fileMatchProcessorWrapper.filePathMatches(zipFileFile, relativePath, log)) {
                    // File's relative path matches.
                    // Don't use the last modified time from the individual zipEntry objects,
                    // we use the last modified time for the zipfile itself instead.
                    fileMatches.put(fileMatchProcessorWrapper, newClasspathResource(zipFileFile,
                            requiredPrefix + relativePath, relativePath, zipEntry));
                }
            }
        }
        fileToLastModified.put(zipFileFile, zipFileFile.lastModified());
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