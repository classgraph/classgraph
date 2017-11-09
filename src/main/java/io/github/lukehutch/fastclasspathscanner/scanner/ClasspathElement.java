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

import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

/** A classpath element (a directory or jarfile on the classpath). */
abstract class ClasspathElement {
    /** The path of the classpath element relative to the current directory. */
    final RelativePath classpathEltPath;

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
    List<RelativePath> childClasspathElts;

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
    ClasspathElement(final RelativePath classpathEltPath, final ScanSpec scanSpec, final boolean scanFiles,
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
    static ClasspathElement newInstance(final RelativePath classpathRelativePath, final boolean scanFiles,
            final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler,
            final WorkQueue<RelativePath> workQueue, final InterruptionChecker interruptionChecker,
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
        final HashSet<String> maskedRelativePaths = new HashSet<>();
        for (final ClasspathResource res : classfileMatches) {
            // Don't mask module-info.class, since all modules need this classfile to be read
            if (!res.pathRelativeToClasspathPrefix.equals("module-info.class")
                    && !res.pathRelativeToClasspathPrefix.endsWith("/module-info.class")) {
                if (!classpathRelativePathsFound.add(res.pathRelativeToClasspathPrefix)) {
                    // This relative path has been encountered more than once;
                    // mask the second and subsequent occurrences of the path
                    maskedRelativePaths.add(res.pathRelativeToClasspathPrefix);
                }
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
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Call FileMatchProcessors for any whitelisted matches found within this classpath element. */
    void callFileMatchProcessors(final ScanResult scanResult, final LogNode log)
            throws InterruptedException, ExecutionException {
        if (fileMatches != null) {
            final LogNode subLog = log == null ? null
                    : log.log("Calling FileMatchProcessors for classpath element " + this);
            for (final Entry<FileMatchProcessorWrapper, List<ClasspathResource>> ent : fileMatches.entrySet()) {
                final FileMatchProcessorWrapper fileMatchProcessorWrapper = ent.getKey();
                for (final ClasspathResource fileMatchResource : ent.getValue()) {
                    try {
                        final LogNode logNode = subLog == null ? null
                                : subLog.log("Calling MatchProcessor for matching file " + fileMatchResource);
                        // Process the file match (may call fileMatchResource.open())
                        fileMatchProcessorWrapper.processMatch(fileMatchResource, log);
                        if (logNode != null) {
                            logNode.addElapsedTime();
                        }
                    } catch (final IOException e) {
                        if (subLog != null) {
                            subLog.log("Exception while opening file " + fileMatchResource, e);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while calling FileMatchProcessor for file " + fileMatchResource,
                                    e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                    interruptionChecker.check();
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Parse any classfiles for any whitelisted classes found within this classpath element. */
    void parseClassfiles(final ClassfileBinaryParser classfileBinaryParser, final int classfileStartIdx,
            final int classfileEndIdx, final ConcurrentHashMap<String, String> stringInternMap,
            final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final LogNode log) throws Exception {
        for (int i = classfileStartIdx; i < classfileEndIdx; i++) {
            final ClasspathResource classfileResource = classfileMatches.get(i);
            try {
                final LogNode logNode = log == null ? null : log.log("Parsing classfile " + classfileResource);
                // Parse classpath binary format, creating a ClassInfoUnlinked object
                final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                        .readClassInfoFromClassfileHeader(this, classfileResource.pathRelativeToClasspathPrefix,
                                // Open classfile as an InputStream
                                /* inputStream = */ classfileResource.open(), //
                                scanSpec, stringInternMap, logNode);
                // If class was successfully read, output new ClassInfoUnlinked object
                if (thisClassInfoUnlinked != null) {
                    classInfoUnlinked.add(thisClassInfoUnlinked);
                    thisClassInfoUnlinked.logTo(logNode);
                }
                if (logNode != null) {
                    logNode.addElapsedTime();
                }
            } catch (final IOException e) {
                if (log != null) {
                    log.log("IOException while attempting to read classfile " + classfileResource + " -- skipping",
                            e);
                }
            } catch (final Throwable e) {
                if (log != null) {
                    log.log("Exception while parsing classfile " + classfileResource, e);
                }
                // Re-throw
                throw e;
            } finally {
                // Close classfile InputStream (and any associated ZipEntry); recycle ZipFile if applicable
                classfileResource.close();
            }
            interruptionChecker.check();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Scan the classpath element */
    public abstract void scanPaths(LogNode log);

    /** Close the classpath element's resources, if needed (this closes and frees any open ZipFiles). */
    public abstract void close();
}