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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner.ClassMatcher;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Log.DeferredLog;

public class RecursiveScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    /**
     * If true, scans the classpath for matching files, and calls any match processors if a match is identified. If
     * false, only scans timestamps of files.
     */
    private boolean scanTimestampsOnly;

    /** The class matchers. */
    private final ArrayList<ClassMatcher> classMatchers;

    /** A map from class name to static final fields to match within the class. */
    private final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch;

    /**
     * A map from (className + "." + staticFinalFieldName) to StaticFinalFieldMatchProcessor(s) that should be
     * called if that class name and static final field name is encountered with a static constant initializer
     * during scan.
     */
    private final Map<String, ArrayList<StaticFinalFieldMatchProcessor>> //
    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FilePathTesterAndMatchProcessorWrapper> filePathTestersAndMatchProcessorWrappers = //
            new ArrayList<>();

    /**
     * The set of absolute directory/zipfile paths scanned (after symlink resolution), to prevent the same resource
     * from being scanned twice.
     */
    private final Set<String> previouslyScannedCanonicalPaths = new HashSet<>();

    /**
     * The set of relative file paths scanned (without symlink resolution), to allow for classpath masking of
     * resources (only the first resource with a given relative path should be visible within the classpath, as per
     * Java conventions).
     */
    private final Set<String> previouslyScannedRelativePaths = new HashSet<>();

    /** A map holding interned strings, to save memory. */
    final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /** Used to interrupt all threads when any thread is interrupted. */
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();

    /**
     * When the main thread hierarchically scans the classpath, it places the paths of any classfiles that are found
     * into this queue, to be fed into classfile scanner threads.
     */
    private final LinkedBlockingQueue<ClassfileResource> classfileResourcesToScan = //
            new LinkedBlockingQueue<ClassfileResource>();

    /** Classfile scanner threads place ClassInfoUnlinked objects into this queue for each classfile scanned. */
    private final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked = //
            new LinkedBlockingQueue<ClassInfoUnlinked>();

    /** A map from class name to ClassInfo object for the class. */
    private final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();

    /** The class graph builder. */
    private ClassGraphBuilder classGraphBuilder;

    // -------------------------------------------------------------------------------------------------------------

    /** The total number of regular directories scanned. */
    private final AtomicInteger numDirsScanned = new AtomicInteger();

    /** The total number of jarfile-internal directories scanned. */
    private final AtomicInteger numJarfileDirsScanned = new AtomicInteger();

    /** The total number of regular files scanned. */
    private final AtomicInteger numFilesScanned = new AtomicInteger();

    /** The total number of jarfile-internal files scanned. */
    private final AtomicInteger numJarfileFilesScanned = new AtomicInteger();

    /** The total number of jarfiles scanned. */
    private final AtomicInteger numJarfilesScanned = new AtomicInteger();

    /** The total number of classfiles scanned. */
    private final AtomicInteger numClassfilesScanned = new AtomicInteger();

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final File classpathElt, final String relativePathStr);
    }

    /** An interface called when the corresponding FilePathTester returns true. */
    public static interface FileMatchProcessorWrapper {
        public void processMatch(final File classpathElt, final String relativePath, final InputStream inputStream,
                final long fileSize) throws IOException;
    }

    private static class FilePathTesterAndMatchProcessorWrapper {
        FilePathTester filePathTester;
        FileMatchProcessorWrapper fileMatchProcessorWrapper;

        public FilePathTesterAndMatchProcessorWrapper(final FilePathTester filePathTester,
                final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
        }
    }

    public void addFilePathMatcher(final FilePathTester filePathTester,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        filePathTestersAndMatchProcessorWrappers
                .add(new FilePathTesterAndMatchProcessorWrapper(filePathTester, fileMatchProcessorWrapper));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursive classpath scanner. Pass in a specification of whitelisted packages/jars to scan and blacklisted
     * packages/jars not to scan, where blacklisted entries are prefixed with the '-' character.
     * 
     * Examples of values for scanSpecs:
     * 
     * ["com.x"] => scans the package "com.x" and its sub-packages in all directories and jars on the classpath.
     * 
     * ["com.x", "-com.x.y"] => scans "com.x" and all sub-packages except "com.x.y" in all directories and jars on
     * the classpath.
     * 
     * ["com.x", "-com.x.y", "jar:deploy.jar"] => scans "com.x" and all sub-packages except "com.x.y", but only
     * looks in jars named "deploy.jar" on the classpath (i.e. whitelisting a "jar:" entry prevents non-jar entries
     * from being searched). Note that only the leafname of a jarfile can be specified.
     * 
     * ["com.x", "-jar:irrelevant.jar"] => scans "com.x" and all sub-packages in all directories and jars on the
     * classpath *except* "irrelevant.jar" (i.e. blacklisting a jarfile doesn't prevent directories from being
     * scanned the way that whitelisting a jarfile does).
     * 
     * ["com.x", "jar:"] => scans "com.x" and all sub-packages, but only looks in jarfiles on the classpath, doesn't
     * scan directories (i.e. all jars are whitelisted, and whitelisting jarfiles prevents non-jars (directories)
     * from being scanned).
     * 
     * ["com.x", "-jar:"] => scans "com.x" and all sub-packages, but only looks in directories on the classpath,
     * doesn't scan jarfiles (i.e. all jars are blacklisted.)
     * 
     * @param fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors
     */
    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec,
            final ArrayList<ClassMatcher> classMatchers,
            final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch,
            final Map<String, ArrayList<StaticFinalFieldMatchProcessor>> // 
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
        this.classMatchers = classMatchers;
        this.classNameToStaticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch;
        this.fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors = // 
                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true if the canonical path (after resolving symlinks and getting absolute path) for this dir, jarfile
     * or file hasn't been seen before during this scan.
     */
    private boolean previouslyScanned(final File fileOrDir) {
        try {
            // Get canonical path (resolve symlinks, and make the path absolute), then see if this canonical path
            // has been scanned before
            return !previouslyScannedCanonicalPaths.add(fileOrDir.getCanonicalPath());
        } catch (final IOException | SecurityException e) {
            // If something goes wrong while getting the real path, just return true.
            return true;
        }
    }

    /**
     * Return true if the relative path for this file hasn't been seen before during this scan (indicating that a
     * resource at this path relative to the classpath hasn't been scanned).
     */
    private boolean previouslyScanned(final String relativePath) {
        return !previouslyScannedRelativePaths.add(relativePath);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            boolean inWhitelistedPath) {
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning directory: " + dir);
        }
        updateLastModifiedTimestamp(dir.lastModified());
        numDirsScanned.incrementAndGet();

        final String dirPath = dir.getPath();
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        final ScanSpecPathMatch matchStatus = scanSpec.pathWhitelistMatchStatus(dirRelativePath);
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached non-whitelisted (or blacklisted) directory: " + dirRelativePath);
            }
            return;
        } else if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Reached a whitelisted path -- can start scanning directories and files from this point
            inWhitelistedPath = true;
        }

        final long startTime = System.nanoTime();
        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (FastClasspathScanner.verbose) {
                Log.log(4, "Invalid directory " + dir);
            }
            return;
        }
        for (final File fileInDir : filesInDir) {
            interruptionChecker.check();
            if (fileInDir.isDirectory()) {
                if (inWhitelistedPath //
                        || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
                    // Recurse into subdirectory
                    scanDir(classpathElt, fileInDir, ignorePrefixLen, inWhitelistedPath);
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

                // Make sure file with same absolute path or same relative path hasn't been scanned before.
                // (N.B. don't inline these two different calls to previouslyScanned() into a single expression
                // using "||", because they have intentional side effects)
                final boolean subFilePreviouslyScannedCanonical = previouslyScanned(fileInDir);
                final boolean subFilePreviouslyScannedRelative = previouslyScanned(fileInDirRelativePath);
                if (subFilePreviouslyScannedRelative || subFilePreviouslyScannedCanonical) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Reached duplicate path, ignoring: " + fileInDirRelativePath);
                    }
                    continue;
                }

                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Found whitelisted file: " + fileInDirRelativePath);
                }
                updateLastModifiedTimestamp(fileInDir.lastModified());

                if (!scanTimestampsOnly) {
                    boolean matchedFile = false;

                    // Store relative paths of any classfiles encountered
                    if (fileInDirRelativePath.endsWith(".class")) {
                        matchedFile = true;
                        classfileResourcesToScan.add(new ClassfileResource(classpathElt, fileInDirRelativePath));
                        numClassfilesScanned.incrementAndGet();
                    }

                    // Match file paths against path patterns
                    for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                    filePathTestersAndMatchProcessorWrappers) {
                        if (fileMatcher.filePathTester.filePathMatches(classpathElt, fileInDirRelativePath)) {
                            // File's relative path matches.
                            matchedFile = true;
                            final long fileStartTime = System.nanoTime();
                            try (FileInputStream inputStream = new FileInputStream(fileInDir)) {
                                fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt,
                                        fileInDirRelativePath, inputStream, fileInDir.length());
                            } catch (final Exception e) {
                                throw new RuntimeException(
                                        "Exception while processing match " + fileInDirRelativePath, e);
                            }
                            if (FastClasspathScanner.verbose) {
                                Log.log(4, "Processed file match " + fileInDirRelativePath,
                                        System.nanoTime() - fileStartTime);
                            }
                        }
                    }
                    if (matchedFile) {
                        numFilesScanned.incrementAndGet();
                    }
                }
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanned directory " + dir + " and subdirectories", System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File classpathElt, final ZipFile zipFile) {
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning jarfile: " + classpathElt);
        }
        final long startTime = System.nanoTime();
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            interruptionChecker.check();

            final ZipEntry zipEntry = entries.nextElement();
            String relativePath = zipEntry.getName();
            if (relativePath.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                relativePath = relativePath.substring(1);
            }

            // Ignore directory entries, they are not needed
            final boolean isDir = zipEntry.isDirectory();
            if (isDir) {
                if (prevParentMatchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
                    numJarfileDirsScanned.incrementAndGet();
                    if (FastClasspathScanner.verbose) {
                        numJarfileFilesScanned.incrementAndGet();
                    }
                }
                continue;
            }

            // Only accept first instance of a given relative path within classpath.
            if (previouslyScanned(relativePath)) {
                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Reached duplicate relative path, ignoring: " + relativePath);
                }
                continue;
            }

            // Get match status of the parent directory if this zipentry file's relative path
            // (or reuse the last match status for speed, if the directory name hasn't changed). 
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final ScanSpecPathMatch parentMatchStatus = // 
                    prevParentRelativePath == null || !parentRelativePath.equals(prevParentRelativePath)
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

            if (FastClasspathScanner.verbose) {
                Log.log(3, "Found whitelisted file in jarfile: " + relativePath);
            }

            boolean matchedFile = false;

            // Store relative paths of any classfiles encountered
            if (relativePath.endsWith(".class")) {
                matchedFile = true;
                classfileResourcesToScan.add(new ClassfileResource(classpathElt, relativePath));
                numClassfilesScanned.incrementAndGet();
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            filePathTestersAndMatchProcessorWrappers) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath)) {
                    // File's relative path matches.
                    try {
                        matchedFile = true;
                        final long fileStartTime = System.nanoTime();
                        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                            fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt, relativePath,
                                    inputStream, zipEntry.getSize());
                        }
                        if (FastClasspathScanner.verbose) {
                            Log.log(4, "Processed file match " + relativePath, System.nanoTime() - fileStartTime);
                        }
                    } catch (final Exception e) {
                        throw new RuntimeException("Exception while processing match " + relativePath, e);
                    }
                }
            }
            if (matchedFile) {
                numJarfileFilesScanned.incrementAndGet();
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(4, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     * 
     * @param scanTimestampsOnly
     *            If true, scans the classpath for matching files, and calls any match processors if a match is
     *            identified. If false, only scans timestamps of files.
     */
    private synchronized void scan(final boolean scanTimestampsOnly, ExecutorService executorService,
            int numWorkerThreads) {
        final long scanStart = System.nanoTime();
        // Get classpath elements
        final List<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log("Classpath elements: " + classpathFinder.getUniqueClasspathElements());
            Log.log(1, "Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : ""));
        }

        // Initialize scan
        this.scanTimestampsOnly = scanTimestampsOnly;
        previouslyScannedCanonicalPaths.clear();
        previouslyScannedRelativePaths.clear();
        numDirsScanned.set(0);
        numFilesScanned.set(0);
        numJarfileDirsScanned.set(0);
        numJarfileFilesScanned.set(0);
        numJarfilesScanned.set(0);
        numClassfilesScanned.set(0);
        classfileResourcesToScan.clear();
        classInfoUnlinked.clear();
        if (!scanTimestampsOnly) {
            classNameToClassInfo.clear();
        }

        // ---------------------------------------------------------------------------------------------------------
        // Start worker threads that take ClassfileResource objects and scan the classfile binary, mapping to
        // ClassInfoUnlinked objects, then the final thread that maps ClassInfoUnlinked objects to ClassInfo
        // objects that are cross-linked with each other.
        // ---------------------------------------------------------------------------------------------------------

        List<Future<Void>> futures = null;
        List<DeferredLog> logs = null;
        boolean startWorkerThreads = !scanTimestampsOnly && executorService != null && numWorkerThreads >= 2;
        int numClassfileParserThreads = startWorkerThreads ? numWorkerThreads - 1 : 0;
        if (startWorkerThreads) {
            // Start classfile parser threads -- these consume ClassfileResource objects and map to
            // ClassInfoUnlinked objects for each classfile scanned
            futures = new ArrayList<>(numClassfileParserThreads + 1);
            logs = new ArrayList<>(numClassfileParserThreads + 1);
            for (int i = 0; i < numClassfileParserThreads; i++) {
                // Create and start a new ClassfileBinaryParserCaller thread that consumes entries from
                // the classfileResourcesToScan queue and creates objects in the classInfoUnlinked queue
                DeferredLog log = new DeferredLog();
                logs.add(log);
                futures.add(executorService.submit(
                        new ClassfileBinaryParserCaller(classfileResourcesToScan, classInfoUnlinked, scanSpec,
                                classNameToStaticFinalFieldsToMatch, stringInternMap, interruptionChecker, log)));
            }

            // Add one empty placeholder log, so that there is one log per thread (it is not used for last thread)
            logs.add(new DeferredLog());
            // Start final thread that creates cross-linked ClassInfo objects from each ClassInfoUnlinked object
            futures.add(executorService.submit(new ClassInfoLinkerCaller(numClassfileParserThreads,
                    classInfoUnlinked, classNameToClassInfo, interruptionChecker)));

        } else if (FastClasspathScanner.verbose) {
            Log.log("Scanning in single-threaded mode");
        }

        // ---------------------------------------------------------------------------------------------------------
        // Main thread: recursively scan within each directory and jar on the classpath, producing ClassfileResource
        // objects that are fed into worker threads
        // ---------------------------------------------------------------------------------------------------------

        for (final File classpathElt : uniqueClasspathElts) {
            final long eltStartTime = System.nanoTime();
            final String path = classpathElt.getPath();
            // ClasspathFinder determines that anything that is not a directory is a jarfile
            final boolean isDirectory = classpathElt.isDirectory(), isJar = !isDirectory;
            if (previouslyScanned(classpathElt)) {
                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Reached duplicate classpath entry, ignoring: " + classpathElt);
                }
                continue;
            }
            if (FastClasspathScanner.verbose) {
                Log.log(2, "Found " + (isDirectory ? "directory" : "jar") + " on classpath: " + path);
            }

            if (isDirectory && scanSpec.scanNonJars) {

                // -------------------------------------------------------------------------------------------------
                // Recursively scan within a directory tree, and call file MatchProcessors on any matches.
                // Also sends a ClassfileResource object for each encountered classfile to the worker threads. 
                // -------------------------------------------------------------------------------------------------

                // Scan dirs recursively, looking for matching paths; call FileMatchProcessors on any matches.
                // Also store relative paths of all whitelisted classfiles in whitelistedClassfileRelativePaths.
                scanDir(classpathElt, classpathElt, /* ignorePrefixLen = */ path.length() + 1,
                        /* inWhitelistedPath = */ false);

                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Scanned classpath directory " + classpathElt, System.nanoTime() - eltStartTime);
                }

            } else if (isJar && scanSpec.scanJars) {

                // -------------------------------------------------------------------------------------------------
                // Scan within a jar/zipfile, and call file MatchProcessors on any matches
                // Also sends a ClassfileResource object for each encountered classfile to the worker threads. 
                // -------------------------------------------------------------------------------------------------

                if (!scanSpec.jarIsWhitelisted(classpathElt.getName())) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Skipping jarfile that did not match whitelist/blacklist criteria: "
                                + classpathElt.getName());
                    }
                    continue;
                }

                // Use the timestamp of the jar/zipfile as the timestamp for all files,
                // since the timestamps within the zip directory may be unreliable.
                updateLastModifiedTimestamp(classpathElt.lastModified());
                numJarfilesScanned.incrementAndGet();

                if (!scanTimestampsOnly) {
                    // Don't actually scan the contents of the zipfile if we're only scanning timestamps,
                    // since only the timestamp of the zipfile itself will be used.
                    try (ZipFile zipFile = new ZipFile(classpathElt)) {
                        scanZipfile(classpathElt, zipFile);
                    } catch (final IOException e) {
                        // Ignore, can only be thrown by zipFile.close() 
                    }

                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Scanned classpath jarfile " + classpathElt, System.nanoTime() - eltStartTime);
                    }
                }

            } else {
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Skipping classpath element " + path);
                }
            }
        }
        // Add one end-of-queue marker for each thread to use as a poison pill
        for (int i = 0, n = numClassfileParserThreads == 0 ? 1 : numClassfileParserThreads; i < n; i++) {
            classfileResourcesToScan.add(ClassfileResource.END_OF_QUEUE);
        }

        if (!scanTimestampsOnly) {

            // -----------------------------------------------------------------------------------------------------
            // Wait for worker thread completion, and then flush out worker logs in order
            // -----------------------------------------------------------------------------------------------------

            if (startWorkerThreads) {
                for (int i = 0; i < futures.size(); i++) {
                    Future<Void> future = futures.get(i);
                    DeferredLog log = logs.get(i);
                    try {
                        // Wait for worker thread completion
                        future.get();
                    } catch (InterruptedException e) {
                        // This main thread was interrupted. Re-set the interrupt status.
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof ScanningInterruptedException) {
                            // A worker thread was interrupted (the worker will throw ScanningInterruptedException,
                            // which is wrapped in an ExecutionException). Set the interrupt status of main thread.
                            Thread.currentThread().interrupt();
                        } else {
                            throw new RuntimeException("Exception while parsing classfiles", e);
                        }
                    }
                    // Show log output for worker thread
                    log.flush();
                    // Throw ScanningInterruptedException, and also interrupt the other worker threads.
                    interruptionChecker.check();
                }
            } else {
                // SINGLE-THREADED CASE

                // If worker threads were not started, i.e. if running on a single thread, then need to manually
                // start the two worker processing stages on the main thread.
                DeferredLog log = new DeferredLog();
                new ClassfileBinaryParserCaller(classfileResourcesToScan, classInfoUnlinked, scanSpec,
                        classNameToStaticFinalFieldsToMatch, stringInternMap, interruptionChecker, log).call();
                new ClassInfoLinkerCaller(numClassfileParserThreads, classInfoUnlinked, classNameToClassInfo,
                        interruptionChecker).call();
                log.flush();
            }

            // -----------------------------------------------------------------------------------------------------
            // Build the class graph out of the ClassInfo objects.
            // -----------------------------------------------------------------------------------------------------

            // Build class, interface and annotation graph 
            final long graphStartTime = System.nanoTime();
            classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);
            if (FastClasspathScanner.verbose) {
                Log.log(2, "Built class graph", System.nanoTime() - graphStartTime);
            }

            // -----------------------------------------------------------------------------------------------------
            // Call MatchProcessors on any matching classes and/or static final fields
            // -----------------------------------------------------------------------------------------------------

            // Call any class, interface and annotation MatchProcessors
            for (final ClassMatcher classMatcher : classMatchers) {
                classMatcher.lookForMatches();
            }

            // Call static final field match processors on matching fields
            if (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors != null) {
                for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                    if (classInfo.fieldValues != null) {
                        for (final Entry<String, Object> ent : classInfo.fieldValues.entrySet()) {
                            final String fieldName = ent.getKey();
                            final Object constValue = ent.getValue();
                            final String fullyQualifiedFieldName = classInfo.className + "." + fieldName;
                            final ArrayList<StaticFinalFieldMatchProcessor> staticFinalFieldMatchProcessors = //
                                    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors
                                            .get(fullyQualifiedFieldName);
                            if (staticFinalFieldMatchProcessors != null) {
                                String constValueStrRep = (constValue instanceof Character)
                                        ? '\'' + constValue.toString().replace("'", "\\'") + '\''
                                        : (constValue instanceof String)
                                                ? '"' + constValue.toString().replace("\"", "\\\"") + '"'
                                                : constValue.toString();
                                if (FastClasspathScanner.verbose) {
                                    Log.log(1, "Calling MatchProcessor for static final field "
                                            + classInfo.className + "." + fieldName + " = " + constValueStrRep);
                                }
                                for (final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor : //
                                staticFinalFieldMatchProcessors) {
                                    staticFinalFieldMatchProcessor.processMatch(classInfo.className, fieldName,
                                            constValue);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (FastClasspathScanner.verbose) {
            Log.log(1, "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                    + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                    + "; jarfile-internal directories: " + numJarfileDirsScanned + "; jarfile-internal files: "
                    + numJarfileFilesScanned + "; classfiles: " + numClassfilesScanned);
        }
        if (FastClasspathScanner.verbose) {
            Log.log("Finished scan", System.nanoTime() - scanStart);
        }
    }

    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker threads.
     * @param numWorkerThreads
     *            The number of worker threads to use while scanning, not including the main thread. Will not use
     *            worker threads if numWorkerThreads < 2.
     */
    public void scan(ExecutorService executorService, int numWorkerThreads) {
        scan(/* scanTimestampsOnly = */false, executorService, numWorkerThreads);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class, interface and annotation graph builder, containing the results of the last full scan, or null
     * if a scan has not yet been completed.
     */
    public ClassGraphBuilder getClassGraphBuilder() {
        return classGraphBuilder;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Update the last modified timestamp, given the timestamp of a Path. */
    private void updateLastModifiedTimestamp(final long fileLastModified) {
        // Find max last modified timestamp, but don't accept values greater than the current time
        lastModified = Math.max(lastModified, Math.min(System.currentTimeMillis(), fileLastModified));
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     * Much faster than standard classpath scanning, because only timestamps are checked, and jarfiles don't have to
     * be opened.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long oldLastModified = this.lastModified;
        if (oldLastModified == 0) {
            return true;
        } else {
            scan(/* scanTimestampsOnly = */ true, /* executorService = */ null, /* numWorkerThreads = */ 0);
            final long newLastModified = this.lastModified;
            return newLastModified > oldLastModified;
        }
    }

    /**
     * Returns the maximum "last modified" timestamp in the classpath (in epoch millis), or zero if scan() has not
     * yet been called (or if nothing was found on the classpath).
     * 
     * The returned timestamp should be less than the current system time if the timestamps of files on the
     * classpath and the system time are accurate. Therefore, if anything changes on the classpath, this value
     * should increase.
     */
    public long classpathContentsLastModifiedTime() {
        return this.lastModified;
    }
}
