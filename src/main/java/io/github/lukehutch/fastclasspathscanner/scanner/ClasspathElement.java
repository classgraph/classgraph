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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathUtils;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

/** A classpath element (a directory or jarfile on the classpath). */
abstract class ClasspathElement {
    /** The classpath element path. */
    final ClasspathRelativePath classpathEltPath;

    /**
     * If non-null, contains a list of resolved paths for any classpath element roots nested inside this classpath
     * element. (Scanning should stop at a nested classpath element root, otherwise that subtree will be scanned
     * more than once.) N.B. contains only the nested part of the resolved path (the common prefix is removed). Also
     * includes a trailing '/', since only nested directory classpath elements need to be caught (nested jars do not
     * need to be caught, because we don't scan jars-within-jars unless the inner jar is explicitly listed on the
     * classpath).
     */
    Set<String> nestedClasspathRoots;

    /** True if there was an exception when trying to open this classpath element (e.g. a corrupt ZipFile). */
    boolean ioExceptionOnOpen;

    /**
     * The child classpath elements. These are the entries obtained from Class-Path entries in the manifest file, if
     * this classpath element is a jarfile.
     */
    List<ClasspathRelativePath> childClasspathElts;

    /** The scan spec. */
    final ScanSpec scanSpec;

    /**
     * If true, recursively scan directores, and iterate through ZipEntries inside ZipFiles looking for whitelisted
     * file and classfile matches. If false, only find unique classpath elements.
     */
    private final boolean scanFiles;

    /**
     * Used to detect interruption of threads, and to shut down all workers in the case of interruption or execution
     * exceptions.
     */
    protected InterruptionChecker interruptionChecker;

    /** The list of classpath resources that matched for each FileMatchProcessor. */
    protected MultiMapKeyToList<FileMatchProcessorWrapper, ClasspathResource> fileMatches;

    /** The list of whitelisted classfiles found within this classpath resource, if scanFiles is true. */
    protected List<ClasspathResource> classfileMatches;

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected Map<File, Long> fileToLastModified;

    /** A classpath element (a directory or jarfile on the classpath). */
    ClasspathElement(final ClasspathRelativePath classpathEltPath, final ScanSpec scanSpec, final boolean scanFiles,
            final InterruptionChecker interruptionChecker) {
        this.classpathEltPath = classpathEltPath;
        this.scanSpec = scanSpec;
        this.scanFiles = scanFiles;
        this.interruptionChecker = interruptionChecker;
    }

    /** Return the classpath element's path. */
    @Override
    public String toString() {
        return getClasspathElementFilePath();
    }

    /** Return the classpath element's URL */
    public URL getClasspathElementURL() {
        try {
            return getClasspathElementFile().toURI().toURL();
        } catch (final MalformedURLException e) {
            // Shouldn't happen; File objects should always be able to be turned into URIs and then URLs
            throw new RuntimeException(e);
        }
    }

    /** Return the classpath element's file (directory or jarfile) */
    public File getClasspathElementFile() {
        try {
            return classpathEltPath.getFile();
        } catch (final IOException e) {
            // Shouldn't happen; files have already been screened for IOException during canonicalization
            throw new RuntimeException(e);
        }
    }

    /** The path for this classpath element, possibly including a '!' jar-internal path suffix. */
    public String getClasspathElementFilePath() {
        return classpathEltPath.toString();
    }

    /** Get the ClassLoader(s) to use when trying to load the class. */
    public ClassLoader[] getClassLoaders() {
        return classpathEltPath.getClassLoaders();
    }

    /**
     * Factory for creating a ClasspathElementDir singleton for directory classpath entries or a ClasspathElementZip
     * singleton for jarfile classpath entries.
     */
    static ClasspathElement newInstance(final ClasspathRelativePath classpathRelativePath, final boolean scanFiles,
            final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler,
            final WorkQueue<ClasspathRelativePath> workQueue, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        boolean isDir;
        String canonicalPath;
        File file;
        try {
            file = classpathRelativePath.getFile();
            isDir = classpathRelativePath.isDirectory();
            canonicalPath = classpathRelativePath.getCanonicalPath();
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathRelativePath.getResolvedPath(),
                        e);
            }
            return null;
        }
        final LogNode logNode = log == null ? null
                : log.log(canonicalPath, "Scanning " + (isDir ? "directory " : "jarfile ") + "classpath entry "
                        + classpathRelativePath
                        + (file.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));
        final ClasspathElement newInstance = isDir
                ? new ClasspathElementDir(classpathRelativePath, scanSpec, scanFiles, interruptionChecker, logNode)
                : new ClasspathElementZip(classpathRelativePath, scanSpec, scanFiles, nestedJarHandler, workQueue,
                        interruptionChecker, logNode);
        if (logNode != null) {
            logNode.addElapsedTime();
        }
        return newInstance;
    }

    /**
     * The combination of a classpath element and a relative path within this classpath element.
     */
    static class ClasspathResource {
        final File classpathEltFile;
        final String pathRelativeToClasspathElt;
        final String pathRelativeToClasspathPrefix;

        private ClasspathResource(final File classpathEltFile, final String pathRelativeToClasspathElt,
                final String pathRelativeToClasspathPrefix) {
            this.classpathEltFile = classpathEltFile;
            this.pathRelativeToClasspathElt = pathRelativeToClasspathElt;
            this.pathRelativeToClasspathPrefix = pathRelativeToClasspathPrefix;
        }

        static class ClasspathResourceInDir extends ClasspathResource {
            final File relativePathFile;

            ClasspathResourceInDir(final File classpathEltFile, final String pathRelativeToClasspathElt,
                    final File relativePathFile) {
                super(classpathEltFile, pathRelativeToClasspathElt, pathRelativeToClasspathElt);
                this.relativePathFile = relativePathFile;
            }

            @Override
            public String toString() {
                return ClasspathUtils.getClasspathResourceURL(classpathEltFile, pathRelativeToClasspathElt)
                        .toString();
            }
        }

        static class ClasspathResourceInZipFile extends ClasspathResource {
            final ZipEntry zipEntry;

            ClasspathResourceInZipFile(final File classpathEltFile, final String pathRelativeToClasspathElt,
                    final String pathRelativeToClasspathPrefix, final ZipEntry zipEntry) {
                super(classpathEltFile, pathRelativeToClasspathElt, pathRelativeToClasspathPrefix);
                this.zipEntry = zipEntry;
            }

            @Override
            public String toString() {
                return ClasspathUtils.getClasspathResourceURL(classpathEltFile, pathRelativeToClasspathElt)
                        .toString();
            }
        }

    }

    /** Get the number of classfile matches. */
    public int getNumClassfileMatches() {
        return classfileMatches == null ? 0 : classfileMatches.size();
    }

    /**
     * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
     * earlier classpath element.
     */
    void maskFiles(final int classpathIdx, final HashSet<String> classpathRelativePathsFound, final LogNode log) {
        if (!scanFiles) {
            // Should not happen
            throw new IllegalArgumentException("scanFiles is false");
        }
        // Take the union of classfile and file match relative paths, since matches can be in both lists
        // if a user adds a custom file path matcher that matches paths ending in ".class"
        final HashSet<String> allMatchingRelativePathsForThisClasspathElement = new HashSet<>();
        for (final ClasspathResource res : classfileMatches) {
            allMatchingRelativePathsForThisClasspathElement.add(res.pathRelativeToClasspathPrefix);
        }
        for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
            for (final ClasspathResource classpathResource : ent.getValue()) {
                allMatchingRelativePathsForThisClasspathElement
                        .add(classpathResource.pathRelativeToClasspathPrefix);
            }
        }
        // See which of these paths are masked, if any
        final HashSet<String> maskedRelativePaths = new HashSet<>();
        for (final String match : allMatchingRelativePathsForThisClasspathElement) {
            if (classpathRelativePathsFound.contains(match)) {
                maskedRelativePaths.add(match);
            }
        }
        if (!maskedRelativePaths.isEmpty()) {
            // Replace the lists of matching resources with filtered versions with masked paths removed
            final List<ClasspathResource> filteredClassfileMatches = new ArrayList<>();
            for (final ClasspathResource classfileMatch : classfileMatches) {
                if (!maskedRelativePaths.contains(classfileMatch.pathRelativeToClasspathPrefix)) {
                    filteredClassfileMatches.add(classfileMatch);
                } else {
                    if (log != null) {
                        log.log(String.format("%06d-1", classpathIdx),
                                "Ignoring duplicate (masked) class " + classfileMatch.pathRelativeToClasspathPrefix
                                        .substring(0, classfileMatch.pathRelativeToClasspathPrefix.length() - 6)
                                        .replace('/', '.') + " for classpath element " + classfileMatch);
                    }
                }
            }
            classfileMatches = filteredClassfileMatches;

            final MultiMapKeyToList<FileMatchProcessorWrapper, ClasspathResource> filteredFileMatches = //
                    new MultiMapKeyToList<>();
            for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
                for (final ClasspathResource fileMatch : ent.getValue()) {
                    if (!maskedRelativePaths.contains(fileMatch.pathRelativeToClasspathPrefix)) {
                        filteredFileMatches.put(ent.getKey(), fileMatch);
                    } else {
                        if (log != null) {
                            log.log(String.format("%06d-1", classpathIdx),
                                    "Ignoring duplicate (masked) file path "
                                            + fileMatch.pathRelativeToClasspathPrefix + " in classpath element "
                                            + fileMatch);
                        }
                    }
                }
            }
            fileMatches = filteredFileMatches;
        }
        classpathRelativePathsFound.addAll(allMatchingRelativePathsForThisClasspathElement);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Call FileMatchProcessors for any whitelisted matches found within this classpath element. */
    void callFileMatchProcessors(final ScanResult scanResult, final LogNode log)
            throws InterruptedException, ExecutionException {
        for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
            final FileMatchProcessorWrapper fileMatchProcessorWrapper = ent.getKey();
            for (final ClasspathResource fileMatch : ent.getValue()) {
                try {
                    final LogNode logNode = log == null ? null
                            : log.log("Calling MatchProcessor for matching file " + fileMatch);
                    openInputStreamAndProcessFileMatch(fileMatch, fileMatchProcessorWrapper);
                    if (logNode != null) {
                        logNode.addElapsedTime();
                    }
                } catch (final IOException e) {
                    if (log != null) {
                        log.log("Exception while opening file " + fileMatch, e);
                    }
                } catch (final Throwable e) {
                    if (log != null) {
                        log.log("Exception while calling FileMatchProcessor for file " + fileMatch, e);
                    }
                    scanResult.addMatchProcessorException(e);
                }
                interruptionChecker.check();
            }
        }
    }

    /**
     * Open an input stream and call a FileMatchProcessor on a specific whitelisted match found within this
     * classpath element. Implemented in the directory- and zipfile-specific sublclasses.
     */
    protected abstract void openInputStreamAndProcessFileMatch(ClasspathResource fileMatch,
            FileMatchProcessorWrapper fileMatchProcessorWrapper) throws IOException;

    // -------------------------------------------------------------------------------------------------------------

    /** Parse any classfiles for any whitelisted classes found within this classpath element. */
    void parseClassfiles(final ClassfileBinaryParser classfileBinaryParser, final int classfileStartIdx,
            final int classfileEndIdx, final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final LogNode log) throws Exception {
        for (int i = classfileStartIdx; i < classfileEndIdx; i++) {
            final ClasspathResource classfileResource = classfileMatches.get(i);
            try {
                final LogNode logNode = log == null ? null : log.log("Parsing classfile " + classfileResource);
                openInputStreamAndParseClassfile(classfileResource, classfileBinaryParser, scanSpec,
                        stringInternMap, classInfoUnlinked, logNode);
                if (logNode != null) {
                    logNode.addElapsedTime();
                }
            } catch (final IOException e) {
                if (log != null) {
                    log.log("IOException while attempting to read classfile " + classfileResource + " -- skipping",
                            e);
                }
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Exception while parsing classfile " + classfileResource, e);
                }
                // Re-throw
                throw e;
            }
            interruptionChecker.check();
        }
    }

    /**
     * Open an input stream and parse a specific classfile found within this classpath element. Implemented in the
     * directory- and zipfile-specific sublclasses.
     */
    protected abstract void openInputStreamAndParseClassfile(final ClasspathResource classfileResource,
            final ClassfileBinaryParser classfileBinaryParser, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final LogNode log)
            throws IOException, InterruptedException;

    // -------------------------------------------------------------------------------------------------------------

    /** Scan the classpath element */
    public abstract void scanPaths(LogNode log);

    /** Close the classpath element's resources. (Used by zipfile-specific subclass.) */
    public abstract void close();
}