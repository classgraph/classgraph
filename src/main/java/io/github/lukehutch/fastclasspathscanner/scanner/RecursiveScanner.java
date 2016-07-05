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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class RecursiveScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FilePathTesterAndMatchProcessorWrapper> filePathTestersAndMatchProcessorWrappers = //
            new ArrayList<>();

    /**
     * The set of absolute paths scanned (after symlink resolution), to prevent the same resource from being scanned
     * twice.
     */
    private final Set<String> scannedNormalizedPathDescriptors = new HashSet<>();

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

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

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
     */
    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final Path absolutePath, final String relativePathStr);
    }

    /** An interface called when the corresponding FilePathTester returns true. */
    public static interface FileMatchProcessorWrapper {
        public void processMatch(final Path absolutePath, final String relativePathStr, BasicFileAttributes attrs)
                throws IOException;
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
     * Return true if the real path for this path hasn't been seen before during this scan. (Getting the real path
     * resolves symlinks.)
     */
    private boolean isNewUniqueRealPath(final Path path) {
        try {
            // Resolve symlinks, make the path absolute, then get the path as a URI
            Path normalizedPath = path.toRealPath();
            String normalizedPathDescriptor = normalizedPath.getFileSystem().toString() + "\t"
                    + normalizedPath.toString();
            boolean isUnique = scannedNormalizedPathDescriptors.add(normalizedPathDescriptor);
            if (!isUnique && FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate classpath resource, ignoring: " + path);
            }
            return isUnique;
        } catch (final IOException e) {
            // If something goes wrong while getting the real path, just return true.
            return true;
        }
    }

    /** Relativize a path relative to a base path, then replace separators with '/'. */
    private static String toRelativeUnixPathStr(final Path base, final Path path) {
        final Path relativePath = base.relativize(path);
        String separator = relativePath.getFileSystem().getSeparator();
        if (separator.length() != 1) {
            throw new RuntimeException("Bad path component separator: " + separator);
        }
        char separatorChar = separator.charAt(0);
        if (separatorChar == '/') {
            return relativePath.toString();
        } else {
            return relativePath.toString().replace(separatorChar, '/');
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Scan a file. */
    private void scanFile(final Path absolutePath, final String relativePathStr, final BasicFileAttributes attrs,
            final boolean scanTimestampsOnly) {
        if (!isNewUniqueRealPath(absolutePath)) {
            return;
        }
        if (FastClasspathScanner.verbose) {
            Log.log(2, "Scanning file: " + absolutePath);
        }
        if (!scanTimestampsOnly) {
            final long startTime = System.nanoTime();
            // Match file paths against path patterns
            boolean filePathMatches = false;
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : // 
            filePathTestersAndMatchProcessorWrappers) {
                if (fileMatcher.filePathTester.filePathMatches(absolutePath, relativePathStr)) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Calling MatchProcessor for file " + absolutePath);
                    }
                    try {
                        fileMatcher.fileMatchProcessorWrapper.processMatch(absolutePath, relativePathStr, attrs);
                    } catch (final Exception e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(4, "Exception while processing file " + absolutePath + ": " + e.getMessage());
                        }
                    }
                    filePathMatches = true;
                }
            }
            if (FastClasspathScanner.verbose && filePathMatches) {
                Log.log(4, "Scanned file " + absolutePath, System.nanoTime() - startTime);
            }
        }
    }

    private void recursiveScan(final Path base, final boolean isJar, final boolean scanTimestampsOnly)
            throws IOException {
        // It's important not to resolve links when normalizing the base path of a classpath element,
        // because pathnames are supposed to be correlated with the package hierarchy. 
        Files.walkFileTree(base.toRealPath(LinkOption.NOFOLLOW_LINKS), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dirPath, final BasicFileAttributes attrs)
                    throws IOException {
                (isJar ? numJarfileDirsScanned : numDirsScanned).incrementAndGet();
                final String relativePathStr = toRelativeUnixPathStr(base, dirPath) + "/";
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Scanning directory: " + dirPath);
                }
                if (!isNewUniqueRealPath(dirPath)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                switch (scanSpec.pathWhitelistMatchStatus(relativePathStr)) {
                case WITHIN_BLACKLISTED_PATH:
                case NOT_WITHIN_WHITELISTED_PATH:
                    // Reached a blacklisted path -- stop scanning files and dirs
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Reached blacklisted path: " + relativePathStr);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                case WITHIN_WHITELISTED_PATH:
                case ANCESTOR_OF_WHITELISTED_PATH:
                case AT_WHITELISTED_CLASS_PACKAGE:
                    // If in the ancestor or descendant of a whitelisted package, keep scanning
                    return FileVisitResult.CONTINUE;
                default:
                    throw new RuntimeException("Unknown match status");
                }
            }

            @Override
            public FileVisitResult visitFile(final Path filePath, final BasicFileAttributes attrs)
                    throws IOException {
                final String relativePathStr = toRelativeUnixPathStr(base, filePath);
                int lastSlashIdx = relativePathStr.lastIndexOf('/');
                final String relativePathStrOfParent = lastSlashIdx < 0 ? "/"
                        : relativePathStr.substring(0, lastSlashIdx + 1);
                final ScanSpecPathMatch matchStatus = scanSpec.pathWhitelistMatchStatus(relativePathStrOfParent);
                boolean performScan = false;
                if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
                    // Within a whitelisted path -- scan all files
                    performScan = true;
                } else if (matchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE) {
                    // Within the package of one or more specifically-whitelisted classes -- only scan this file
                    // if it is a specifically-whitelisted classfile.
                    if (relativePathStr.endsWith(".class")) {
                        final String className = (relativePathStr.substring(0, relativePathStr.length() - 6))
                                .replace('/', '.');
                        performScan = scanSpec.classIsWhitelisted(className);
                    }
                }
                if (performScan) {
                    // Scan the file
                    scanFile(filePath, relativePathStr, attrs, scanTimestampsOnly);
                    (isJar ? numJarfileFilesScanned : numFilesScanned).incrementAndGet();
                    if (!isJar) {
                        updateLastModifiedTimestamp(attrs.lastModifiedTime().toMillis());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * If scanTimestampsOnly is true, scans the classpath for matching files, and calls any match processors if a
     * match is identified. If scanTimestampsOnly is false, only scans timestamps of files.
     */
    private synchronized void scan(final boolean scanTimestampsOnly) {
        final List<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log("Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : ""));
        }
        final Map<String, String> env = new HashMap<>();
        env.put("create", "false");

        scannedNormalizedPathDescriptors.clear();
        numDirsScanned.set(0);
        numFilesScanned.set(0);
        numJarfileDirsScanned.set(0);
        numJarfileFilesScanned.set(0);
        numJarfilesScanned.set(0);

        // Iterate through path elements and recursively scan within each directory and zipfile
        for (final File classpathElt : uniqueClasspathElts) {
            final String path = classpathElt.getPath();
            final boolean isDirectory = classpathElt.isDirectory();
            final boolean isFile = classpathElt.isFile();
            if (!isDirectory && !isFile) {
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Skipping non-file/non-dir on classpath: " + classpathElt.getPath());
                }
            } else {
                final Path classpathEltPath = classpathElt.toPath();
                final boolean isJar = isFile && ClasspathFinder.isJar(path);
                if (FastClasspathScanner.verbose) {
                    Log.log(1, "Found " + (isDirectory ? "directory" : isJar ? "jar" : "file") + " on classpath: "
                            + path);
                }
                try {
                    if (isDirectory && scanSpec.scanNonJars) {
                        // Scan within directory
                        numDirsScanned.incrementAndGet();
                        recursiveScan(classpathEltPath, isJar, scanTimestampsOnly);

                    } else if (isJar && scanSpec.scanJars) {
                        // Need to separately test if jarfiles have been scanned before, rather than testing
                        // if their contained files have been scanned before, to avoid even opening a zipfile
                        // a second time if it has already been scanned
                        if (!isNewUniqueRealPath(classpathEltPath)) {
                            continue;
                        }

                        // For jar/zipfile, use the timestamp of the jar/zipfile as the timestamp for all files,
                        // since the timestamps within the zip directory may be unreliable.
                        if (!scanTimestampsOnly) {
                            // Scan within jar/zipfile
                            final long startTime = System.nanoTime();
                            try (FileSystem zipfs = FileSystems
                                    .newFileSystem(new URI("jar:" + classpathElt.toURI()), env)) {
                                recursiveScan(zipfs.getPath("/"), isJar, scanTimestampsOnly);
                            }
                            if (FastClasspathScanner.verbose) {
                                Log.log(2, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
                            }
                        }
                        updateLastModifiedTimestamp(classpathElt.lastModified());
                        numJarfilesScanned.incrementAndGet();

                    } else if (!isJar && scanSpec.scanNonJars) {
                        // File listed directly on classpath
                        scanFile(classpathEltPath,
                                toRelativeUnixPathStr(classpathEltPath.getParent(), classpathEltPath),
                                Files.readAttributes(classpathElt.toPath(), BasicFileAttributes.class),
                                scanTimestampsOnly);
                        updateLastModifiedTimestamp(classpathElt.lastModified());
                        numFilesScanned.incrementAndGet();

                    } else {
                        if (FastClasspathScanner.verbose) {
                            Log.log(2, "Skipping classpath element due to scan spec restriction: " + path);
                        }
                    }
                } catch (IOException | URISyntaxException e) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Exception while scanning classpath element " + classpathElt + ": "
                                + e.getMessage());
                    }
                }
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(1,
                    "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                            + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                            + "; jarfile-internal directories: " + numJarfileDirsScanned
                            + "; jarfile-internal files: " + numJarfileFilesScanned);
        }
    }

    /** Scans the classpath for matching files, and calls any FileMatchProcessors if a match is identified. */
    public void scan() {
        scan(/* scanTimestampsOnly = */false);
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
            scan(/* scanTimestampsOnly = */true);
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
