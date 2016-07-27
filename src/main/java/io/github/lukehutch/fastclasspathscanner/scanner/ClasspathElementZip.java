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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathElement.ClasspathResource.ClasspathResourceInZipFile;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FilePathTesterAndMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

class ClasspathElementZip extends ClasspathElement {
    private Recycler<ZipFile, IOException> zipFileRecycler;
    private FastManifestParser fastManifestParser;

    ClasspathElementZip(final ClasspathRelativePath classpathElt, final ScanSpec scanSpec, final boolean scanFiles,
            final InterruptionChecker interruptionChecker, final WorkQueue<ClasspathRelativePath> workQueue,
            final LogNode log) {
        super(classpathElt, scanSpec, scanFiles, interruptionChecker, log);
        File classpathEltFile;
        try {
            classpathEltFile = classpathElt.getFile();
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathElt.getResolvedPath(), e);
            }
            ioExceptionOnOpen = true;
            return;
        }
        zipFileRecycler = new Recycler<ZipFile, IOException>() {
            @Override
            public ZipFile newInstance() throws IOException {
                return new ZipFile(classpathEltFile);
            }
        };
        ZipFile zipFile = null;
        try {
            try {
                zipFile = zipFileRecycler.acquire();
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Exception opening zipfile " + classpathEltFile, e);
                }
                ioExceptionOnOpen = true;
                return;
            }
            if (!scanFiles) {
                // If not performing a scan, just get the manifest entry manually
                fastManifestParser = new FastManifestParser(zipFile, zipFile.getEntry("META-INF/MANIFEST.MF"), log);
            } else {
                // Scan for path matches within jarfile, and record ZipEntry objects of matching files.
                // Will parse the manifest file if it finds it.
                final int numEntries = zipFile.size();
                fileMatches = new MultiMapKeyToList<>();
                classfileMatches = new ArrayList<>(numEntries);
                fileToLastModified = new HashMap<>();
                scanZipFile(classpathEltFile, zipFile, log);
            }
            if (fastManifestParser != null && fastManifestParser.classPath != null) {
                if (log != null) {
                    log.log("Found Class-Path entry in manifest of " + classpathElt.getResolvedPath() + ": "
                            + fastManifestParser.classPath);
                }
                // Get the classpath elements from the Class-Path manifest entry
                // (these are space-delimited).
                final String[] manifestClassPathElts = fastManifestParser.classPath.split(" ");

                // Class-Path entries in the manifest file are resolved relative to
                // the dir the manifest's jarfile is contaiin. Get the parent path.
                final String pathOfContainingDir = FastPathResolver.resolve(classpathEltFile.getParent());

                // Create child classpath elements from Class-Path entry
                childClasspathElts = new ArrayList<>(manifestClassPathElts.length);
                for (int i = 0; i < manifestClassPathElts.length; i++) {
                    final String manifestClassPathElt = manifestClassPathElts[i];
                    if (!manifestClassPathElt.isEmpty()) {
                        childClasspathElts
                                .add(new ClasspathRelativePath(pathOfContainingDir, manifestClassPathElt));
                    }
                }

                // Schedule child classpath elements for scanning
                workQueue.addWorkUnits(childClasspathElts);
            }
        } finally {
            zipFileRecycler.release(zipFile);
        }
    }

    /** Scan a zipfile for file path patterns matching the scan spec. */
    private void scanZipFile(final File zipFileFile, final ZipFile zipFile, final LogNode log) {
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        int entryIdx = 0;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            if ((entryIdx++ & 0x3ff) == 0) {
                if (interruptionChecker.checkAndReturn()) {
                    return;
                }
            }
            final ZipEntry zipEntry = entries.nextElement();
            String relativePath = zipEntry.getName();
            if (relativePath.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                relativePath = relativePath.substring(1);
            }

            // Ignore directory entries, they are not needed
            final boolean isDir = zipEntry.isDirectory();
            if (isDir) {
                continue;
            }

            // Get match status of the parent directory if this zipentry file's relative path
            // (or reuse the last match status for speed, if the directory name hasn't changed). 
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
            final ScanSpecPathMatch parentMatchStatus = // 
                    prevParentRelativePath == null || parentRelativePathChanged
                            ? scanSpec.pathWhitelistMatchStatus(parentRelativePath) : prevParentMatchStatus;
            prevParentRelativePath = parentRelativePath;
            prevParentMatchStatus = parentMatchStatus;

            // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
            // that has been specifically-whitelisted
            if (parentMatchStatus != ScanSpecPathMatch.WITHIN_WHITELISTED_PATH
                    && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                            || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                continue;
            }

            if (log != null) {
                log.log("Found whitelisted file in jarfile: " + relativePath);
            }

            // Store relative paths of any classfiles encountered
            if (ClasspathRelativePath.isClassfile(relativePath)) {
                classfileMatches.add(new ClasspathResourceInZipFile(zipFileFile, relativePath, zipEntry));
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                if (fileMatcher.filePathMatches(zipFileFile, relativePath, log)) {
                    // File's relative path matches.
                    // Don't use the last modified time from the individual zipEntry objects,
                    // we use the last modified time for the zipfile itself instead.
                    fileMatches.put(fileMatcher.fileMatchProcessorWrapper,
                            new ClasspathResourceInZipFile(zipFileFile, relativePath, zipEntry));
                }
            }

            // Store entry for manifest file, if present, so that the entry doesn't have to be looked up by name
            if (relativePath.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                fastManifestParser = new FastManifestParser(zipFile, zipEntry, log);
            }
        }
        fileToLastModified.put(zipFileFile, zipFileFile.lastModified());
    }

    @Override
    protected void openInputStreamAndParseClassfile(final ClasspathResource classfileResource,
            final ClassfileBinaryParser classfileBinaryParser, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final LogNode log)
            throws InterruptedException, IOException {
        if (!ioExceptionOnOpen) {
            ZipFile zipFile = null;
            try {
                zipFile = zipFileRecycler.acquire();
                final ZipEntry zipEntry = ((ClasspathResourceInZipFile) classfileResource).zipEntry;
                try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                    // Parse classpath binary format, creating a ClassInfoUnlinked object
                    final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                            .readClassInfoFromClassfileHeader(classfileResource.relativePath, inputStream, scanSpec,
                                    stringInternMap, log);
                    // If class was successfully read, output new ClassInfoUnlinked object
                    if (thisClassInfoUnlinked != null) {
                        classInfoUnlinked.add(thisClassInfoUnlinked);
                        thisClassInfoUnlinked.logTo(log);
                    }
                }
            } finally {
                zipFileRecycler.release(zipFile);
            }
        }
    }

    @Override
    protected void openInputStreamAndProcessFileMatch(final ClasspathResource fileMatchResource,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) throws IOException {
        if (!ioExceptionOnOpen) {
            // Open InputStream on relative path within zipfile
            ZipFile zipFile = null;
            try {
                zipFile = zipFileRecycler.acquire();
                final ZipEntry zipEntry = ((ClasspathResourceInZipFile) fileMatchResource).zipEntry;
                try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                    // Run FileMatcher
                    fileMatchProcessorWrapper.processMatch(fileMatchResource.classpathEltFile,
                            fileMatchResource.relativePath, inputStream, zipEntry.getSize());
                }
            } finally {
                zipFileRecycler.release(zipFile);
            }
        }
    }

    @Override
    public void close() {
        if (zipFileRecycler != null) {
            zipFileRecycler.close();
        }
    }
}