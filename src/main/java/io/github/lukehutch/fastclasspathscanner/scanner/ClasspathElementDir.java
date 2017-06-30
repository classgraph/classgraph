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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathElement.ClasspathResource.ClasspathResourceInDir;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FilePathTesterAndMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;

/** A directory classpath element. */
class ClasspathElementDir extends ClasspathElement {
    private File dir;

    /** A directory classpath element. */
    ClasspathElementDir(final ClasspathRelativePath classpathEltPath, final ScanSpec scanSpec,
            final boolean scanFiles, final InterruptionChecker interruptionChecker, final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles, interruptionChecker, log);
        if (scanFiles) {
            try {
                dir = classpathEltPath.getFile();
            } catch (final IOException e) {
                // Technically can't happen, was already checked by caller
                if (log != null) {
                    log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
                }
                ioExceptionOnOpen = true;
                return;
            }
            fileMatches = new MultiMapKeyToList<>();
            classfileMatches = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    /** Hierarchically scan directory structure for classfiles and matching files. */
    @Override
    public void scanPaths() {
        final HashSet<String> scannedCanonicalPaths = new HashSet<>();
        final int[] entryIdx = new int[1];
        scanDir(dir, dir, /* ignorePrefixLen = */ dir.getPath().length() + 1, /* inWhitelistedPath = */ false,
                scannedCanonicalPaths, entryIdx, log);
    }

    /** Recursively scan a directory for file path patterns matching the scan spec. */
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            final boolean prevInWhitelistedPath, final HashSet<String> scannedCanonicalPaths, final int[] entryIdx,
            final LogNode log) {
        boolean inWhitelistedPath = prevInWhitelistedPath;
        // See if this canonical path has been scanned before, so that recursive scanning doesn't get stuck in
        // an infinite loop due to symlinks
        String canonicalPath;
        try {
            canonicalPath = dir.getCanonicalPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping recursion: " + dir);
                }
                return;
            }
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + dir);
            }
            return;
        }
        final String dirPath = dir.getPath();
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        final ScanSpecPathMatch matchStatus = scanSpec.pathWhitelistMatchStatus(dirRelativePath);
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (log != null) {
                log.log("Reached non-whitelisted (or blacklisted) directory: " + dirRelativePath);
            }
            return;
        } else if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Reached a whitelisted path -- can start scanning directories and files from this point
            inWhitelistedPath = true;
        }
        if (nestedClasspathRoots != null) {
            if (nestedClasspathRoots.contains(dirRelativePath)) {
                if (log != null) {
                    log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                            + dirRelativePath);
                }
                return;
            }
        }

        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (log != null) {
                log.log("Invalid directory " + dir);
            }
            return;
        }
        final LogNode dirLog = log == null ? null
                : log.log(canonicalPath, "Scanning subdirectory path: " + dirRelativePath
                        + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));
        for (final File fileInDir : filesInDir) {
            if ((entryIdx[0]++ & 0xff) == 0) {
                if (interruptionChecker.checkAndReturn()) {
                    return;
                }
            }
            if (fileInDir.isDirectory()) {
                if (inWhitelistedPath //
                        || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
                    // Recurse into subdirectory
                    scanDir(classpathElt, fileInDir, ignorePrefixLen, inWhitelistedPath, scannedCanonicalPaths,
                            entryIdx, dirLog);
                }
            } else if (fileInDir.isFile()) {
                final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                        ? fileInDir.getName() : dirRelativePath + fileInDir.getName();

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
                // that has been specifically-whitelisted
                if (!inWhitelistedPath && (matchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                        || !scanSpec.isSpecificallyWhitelistedClass(fileInDirRelativePath))) {
                    // Ignore files that are siblings of specifically-whitelisted files, but that are not
                    // themselves specifically whitelisted
                    continue;
                }

                if (dirLog != null) {
                    dirLog.log("Found whitelisted file: " + fileInDirRelativePath);
                }
                fileToLastModified.put(fileInDir, fileInDir.lastModified());

                // Store relative paths of any classfiles encountered
                if (ClasspathRelativePath.isClassfile(fileInDirRelativePath)) {
                    classfileMatches
                            .add(new ClasspathResourceInDir(classpathElt, fileInDirRelativePath, fileInDir));
                }

                // Match file paths against path patterns
                for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                    if (fileMatcher.filePathMatches(classpathElt, fileInDirRelativePath, dirLog)) {
                        // File's relative path matches.
                        fileMatches.put(fileMatcher.fileMatchProcessorWrapper,
                                new ClasspathResourceInDir(classpathElt, fileInDirRelativePath, fileInDir));
                    }
                }
            }
        }
        if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
            // Need to timestamp whitelisted directories, so that changes to directory content can be detected.
            // Also need to timestamp ancestors of whitelisted directories, in case a new directory is added
            // that matches whitelist criteria.
            fileToLastModified.put(dir, dir.lastModified());
        }
        if (log != null) {
            log.addElapsedTime();
        }
    }

    /**
     * Open an input stream and call a FileMatchProcessor on a specific whitelisted match found within this
     * directory.
     */
    @Override
    protected void openInputStreamAndProcessFileMatch(final ClasspathResource fileMatchResource,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) throws IOException {
        if (!ioExceptionOnOpen) {
            // Open InputStream on relative path within directory
            final File relativePathFile = ((ClasspathResourceInDir) fileMatchResource).relativePathFile;
            try (InputStream inputStream = new FileInputStream(relativePathFile)) {
                // Run FileMatcher
                fileMatchProcessorWrapper.processMatch(fileMatchResource.classpathEltFile,
                        fileMatchResource.pathRelativeToClasspathElt, inputStream, relativePathFile.length());
            }
        }
    }

    /** Open an input stream and parse a specific classfile found within this directory. */
    @Override
    protected void openInputStreamAndParseClassfile(final ClasspathResource classfileResource,
            final ClassfileBinaryParser classfileBinaryParser, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final LogNode log)
            throws IOException, InterruptedException {
        if (!ioExceptionOnOpen) {
            final File relativePathFile = ((ClasspathResourceInDir) classfileResource).relativePathFile;
            try (InputStream inputStream = new FileInputStream(relativePathFile)) {
                // Parse classpath binary format, creating a ClassInfoUnlinked object
                final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                        .readClassInfoFromClassfileHeader(this, classfileResource.pathRelativeToClasspathElt,
                                inputStream, scanSpec, stringInternMap, log);
                // If class was successfully read, output new ClassInfoUnlinked object
                if (thisClassInfoUnlinked != null) {
                    classInfoUnlinked.add(thisClassInfoUnlinked);
                    thisClassInfoUnlinked.logTo(log);
                }
            }
        }
    }

    /** Nothing to close in the case of dirs. */
    @Override
    public void close() {
    }
}