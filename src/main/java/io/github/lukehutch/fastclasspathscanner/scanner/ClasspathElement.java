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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

abstract class ClasspathElement {
    final File classpathElementFile;
    final ScanSpec scanSpec;
    private final boolean scanFiles;
    List<ClasspathRelativePath> childClasspathElts;
    boolean ioExceptionOnOpen;
    protected InterruptionChecker interruptionChecker;
    protected ThreadLog log;

    MultiMapKeyToList<FileMatchProcessorWrapper, ClasspathResource> fileMatches;
    List<ClasspathResource> classfileMatches;
    Map<File, Long> fileToLastModified;

    ClasspathElement(final ClasspathRelativePath classpathElt, final ScanSpec scanSpec, final boolean scanFiles,
            final InterruptionChecker interruptionChecker, final ThreadLog log) {
        this.scanSpec = scanSpec;
        this.scanFiles = scanFiles;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
        try {
            this.classpathElementFile = classpathElt.getFile();
        } catch (final IOException e) {
            // Shouldn't happen, files have already been screened for this
            throw new RuntimeException(e);
        }
    }

    static ClasspathElement newInstance(final ClasspathRelativePath classpathElt, final boolean scanFiles,
            final ScanSpec scanSpec, final InterruptionChecker interruptionChecker,
            final WorkQueue<ClasspathRelativePath> workQueue, final ThreadLog log) throws IOException {
        boolean isDir;
        try {
            isDir = classpathElt.isDirectory();
        } catch (final IOException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Exception while trying to canonicalize path " + classpathElt.getResolvedPath(), e);
            }
            throw e;
        }
        if (isDir) {
            return new ClasspathElementDir(classpathElt, scanSpec, scanFiles, interruptionChecker, log);
        } else {
            return new ClasspathElementZip(classpathElt, scanSpec, scanFiles, interruptionChecker, workQueue, log);
        }
    }

    /**
     * The combination of a classpath element and a relative path within this classpath element.
     */
    static class ClasspathResource {
        File classpathEltFile;
        String relativePath;

        private ClasspathResource(final File classpathEltFile, final String relativePath) {
            this.classpathEltFile = classpathEltFile;
            this.relativePath = relativePath;
        }

        static class ClasspathResourceInDir extends ClasspathResource {
            File relativePathFile;

            ClasspathResourceInDir(final File classpathEltFile, final String relativePath,
                    final File relativePathFile) {
                super(classpathEltFile, relativePath);
                this.relativePathFile = relativePathFile;
            }
        }

        static class ClasspathResourceInZipFile extends ClasspathResource {
            ZipEntry zipEntry;

            ClasspathResourceInZipFile(final File classpathEltFile, final String relativePath,
                    final ZipEntry zipEntry) {
                super(classpathEltFile, relativePath);
                this.zipEntry = zipEntry;
            }
        }
    }

    public int getNumClassfileMatches() {
        return classfileMatches == null ? 0 : classfileMatches.size();
    }

    /**
     * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
     * earlier classpath element.
     */
    void maskFiles(final HashSet<String> classpathRelativePathsFound) {
        if (!scanFiles) {
            // Should not happen
            throw new IllegalArgumentException("scanFiles is false");
        }
        // Take the union of classfile and file match relative paths, since matches can be in both lists
        // if a user adds a custom file path matcher that matches paths ending in ".class"
        final HashSet<String> matchesUnion = new HashSet<>();
        for (final ClasspathResource res : classfileMatches) {
            matchesUnion.add(res.relativePath);
        }
        for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
            for (final ClasspathResource classpathResource : ent.getValue()) {
                matchesUnion.add(classpathResource.relativePath);
            }
        }
        // See which of these paths are masked, if any
        final HashSet<String> maskedRelativePaths = new HashSet<>();
        for (final String match : matchesUnion) {
            if (classpathRelativePathsFound.contains(match)) {
                maskedRelativePaths.add(match);
            }
        }
        if (!maskedRelativePaths.isEmpty()) {
            // Replace the lists of matching resources with filtered versions with masked paths removed
            final List<ClasspathResource> filteredClassfileMatches = new ArrayList<>();
            for (final ClasspathResource classfileMatch : classfileMatches) {
                if (!maskedRelativePaths.contains(classfileMatch.relativePath)) {
                    filteredClassfileMatches.add(classfileMatch);
                }
            }
            classfileMatches = filteredClassfileMatches;

            final MultiMapKeyToList<FileMatchProcessorWrapper, ClasspathResource> filteredFileMatches = //
                    new MultiMapKeyToList<>();
            for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
                for (final ClasspathResource fileMatch : ent.getValue()) {
                    if (!maskedRelativePaths.contains(fileMatch.relativePath)) {
                        filteredFileMatches.put(ent.getKey(), fileMatch);
                    }
                }
            }
            fileMatches = filteredFileMatches;
        }
    }

    void callFileMatchProcessors() throws InterruptedException, ExecutionException {
        for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
            final FileMatchProcessorWrapper fileMatchProcessorWrapper = ent.getKey();
            for (final ClasspathResource fileMatch : ent.getValue()) {
                try {
                    openInputStreamAndProcessFileMatch(fileMatch, fileMatchProcessorWrapper);
                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log(4, "Exception while opening classpath resource " + fileMatch.classpathEltFile
                                + (fileMatch.classpathEltFile.isFile() ? "!" : "/") + fileMatch.relativePath, e);
                    }
                }
                interruptionChecker.check();
            }
        }
    }

    void parseClassfiles(final ClassfileBinaryParser classfileBinaryParser, final int classfileStartIdx,
            final int classfileEndIdx, final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked)
            throws InterruptedException, ExecutionException {
        for (int i = classfileStartIdx; i < classfileEndIdx; i++) {
            final ClasspathResource classfileResource = classfileMatches.get(i);
            try {
                openInputStreamAndParseClassfile(classfileResource, classfileBinaryParser, scanSpec,
                        stringInternMap, classInfoUnlinked, log);
            } catch (final IOException e) {
                if (FastClasspathScanner.verbose) {
                    log.log("Exception while trying to open " + classfileResource.classpathEltFile
                            + (classfileResource.classpathEltFile.isFile() ? "!" : "/")
                            + classfileResource.relativePath, e);
                }
            }
            interruptionChecker.check();
        }
    }

    protected abstract void openInputStreamAndParseClassfile(final ClasspathResource classfileResource,
            final ClassfileBinaryParser classfileBinaryParser, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final ThreadLog log)
            throws InterruptedException, IOException;

    protected abstract void openInputStreamAndProcessFileMatch(ClasspathResource fileMatch,
            FileMatchProcessorWrapper fileMatchProcessorWrapper) throws IOException;

    public abstract void close();
}