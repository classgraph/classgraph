/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RecursiveScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /**
     * List of directory path prefixes to scan (produced from list of package prefixes passed into the constructor)
     */
    private final String[] whitelistedPaths, blacklistedPaths;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given
     * regexp.
     */
    private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /**
     * A map from classname, to static final field name, to a StaticFinalFieldMatchProcessor that should be called
     * if that class name and static final field name is encountered during scan.
     */
    private final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> //
    classNameToStaticFieldnameToMatchProcessor = new HashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A map from relative path to the information extracted from the class. If the class name is encountered more
     * than once (i.e. if the same class is defined in multiple classpath elements), the second and subsequent class
     * definitions are ignored, because they are masked by the earlier definition.
     */
    private ConcurrentHashMap<String, ClassInfo> classNameToClassInfo = new ConcurrentHashMap<>();

    /** The class and interface graph builder. */
    private ClassGraphBuilder classGraphBuilder;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If this is set to true, then the timestamps of zipfile entries should be used to determine when files inside
     * a zipfile have changed; if set to false, then the timestamp of the zipfile itself is used. Itis recommended
     * to leave this set to false, since zipfile timestamps are less trustworthy than filesystem timestamps.
     */
    private static final boolean USE_ZIPFILE_ENTRY_MODIFICATION_TIMES = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursive classpath scanner. Pass in a list of whitelisted packages to scan, and blacklisted packages to not
     * scan (blacklisted packages are prefixed with the '-' character).
     */
    public RecursiveScanner(final String[] packagesToScan) {
        this.classpathFinder = new ClasspathFinder();

        final HashSet<String> uniqueWhitelistedPaths = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPaths = new HashSet<>();
        for (final String packageToScan : packagesToScan) {
            final String pathToScan = packageToScan.replace('.', '/') + "/";
            final boolean blacklisted = pathToScan.startsWith("-");
            if (blacklisted) {
                final String blacklistedPath = pathToScan.substring(1);
                if (blacklistedPath.equals("/") || blacklistedPath.isEmpty()) {
                    Log.log("Ignoring blacklist of root package, it would prevent all scanning");
                }
                uniqueBlacklistedPaths.add(blacklistedPath);
            } else {
                uniqueWhitelistedPaths.add(pathToScan);
            }
        }
        uniqueWhitelistedPaths.removeAll(uniqueBlacklistedPaths);
        if (uniqueWhitelistedPaths.isEmpty() || uniqueWhitelistedPaths.contains("/")) {
            // Scan all packages
            this.whitelistedPaths = new String[] { "/" };
        } else {
            // Scan whitelisted packages minus blacklisted sub-packages
            this.whitelistedPaths = new String[uniqueWhitelistedPaths.size()];
            int i = 0;
            for (final String path : uniqueWhitelistedPaths) {
                this.whitelistedPaths[i++] = path;
            }
        }
        this.blacklistedPaths = new String[uniqueBlacklistedPaths.size()];
        int i = 0;
        for (final String path : uniqueBlacklistedPaths) {
            this.blacklistedPaths[i++] = path;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Override the automatically-detected classpath with a custom search path. */
    public void overrideClasspath(String classpath) {
        classpathFinder.overrideClasspath(classpath);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle each classfile encountered on the classpath. Implements classpath masking, so classes that are
     * encountered earlier on the classpath hide classes with the same name that are encountered later on the
     * classpath.
     */
    public void processClass(String relativePath, InputStream inputStream) {
        // Make sure this was the first occurrence of the given relativePath on the classpath,
        // to enable masking of classes
        final ClassInfo newClassInfo = new ClassInfo(relativePath);
        final ClassInfo oldClassInfo = classNameToClassInfo.put(newClassInfo.className, newClassInfo);
        if (oldClassInfo == null) {
            // This is the first time we have encountered this class on the classpath
            try {
                ClassfileBinaryParser.readClassInfoFromClassfileHeader(relativePath, inputStream, newClassInfo,
                        classNameToStaticFieldnameToMatchProcessor);
            } catch (IOException e) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Exception while attempting to load classfile " + relativePath + ": " + e.getMessage());
                }
                // Remove ClassInfo, classfile was bad so it's probably not completely initialized
                classNameToClassInfo.remove(newClassInfo.className);
            }
        } else {
            // The new class was masked by a class with the same name earlier in the classpath.
            if (FastClasspathScanner.verbose) {
                Log.log(relativePath.replace('/', '.')
                        + " occurs more than once on classpath, ignoring all but first instance");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final String relativePath);
    }

    /** A class used for associating a FilePathTester with a FileMatchProcessor. */
    public static class FilePathMatcher {
        private final FilePathTester filePathTester;
        private final FileMatchProcessor fileMatchProcessor;

        public FilePathMatcher(final FilePathTester filePathTester, final FileMatchProcessor fileMatchProcessor) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessor = fileMatchProcessor;
        }

        public boolean filePathMatches(final String relativePath) {
            return filePathTester.filePathMatches(relativePath);
        }

        public void processMatch(final String relativePath, final InputStream inputStream,
                final int inputStreamLengthBytes) throws IOException {
            fileMatchProcessor.processMatch(relativePath, inputStream, inputStreamLengthBytes);
        }
    }

    public void addFilePathMatcher(final FilePathMatcher filePathMatcher) {
        this.filePathMatchers.add(filePathMatcher);
    }

    /** An interface used for testing if a class matches specified criteria. */
    public static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    public void addClassMatcher(final ClassMatcher classMatcher) {
        this.classMatchers.add(classMatcher);
    }

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered in a class with the given fully-qualified classname while reading a classfile header.
     */
    public void addStaticFinalFieldProcessor(final String className, final String fieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        HashMap<String, StaticFinalFieldMatchProcessor> fieldNameToMatchProcessor = //
        classNameToStaticFieldnameToMatchProcessor.get(className);
        if (fieldNameToMatchProcessor == null) {
            classNameToStaticFieldnameToMatchProcessor.put(className, fieldNameToMatchProcessor = new HashMap<>(2));
        }
        fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a file.
     */
    private void scanFile(final File file, final String relativePath, final boolean scanTimestampsOnly) {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            // Match file paths against path patterns
            boolean filePathMatches = false;
            for (final FilePathMatcher fileMatcher : filePathMatchers) {
                if (fileMatcher.filePathMatches(relativePath)) {
                    // Open the file as a stream and call the match processor
                    try (final InputStream inputStream = new FileInputStream(file)) {
                        fileMatcher.processMatch(relativePath, inputStream, (int) file.length());
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(e.getMessage() + " while processing file " + file.getPath());
                        }
                    }
                    filePathMatches = true;
                }
            }
            if (FastClasspathScanner.verbose && filePathMatches) {
                Log.log("Found file:    " + relativePath);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns. (Does not recurse into zipfiles within zipfiles.)
     */
    private void scanZipfile(final String zipfilePath, final ZipFile zipFile, final long zipFileLastModified,
            final boolean scanTimestampsOnly) {
        if (FastClasspathScanner.verbose) {
            Log.log("Scanning jar:  " + zipfilePath);
        }
        boolean timestampWarning = false;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Scan for matching filenames
            final ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                // Only process file entries (zipfile indices contain both directory entries and
                // separate file entries for files within each directory, in lexicographic order)
                final String path = entry.getName();
                boolean scanFile = false;
                for (final String whitelistedPath : whitelistedPaths) {
                    if (path.startsWith(whitelistedPath) //
                            || whitelistedPath.equals("/")) {
                        // File path has a whitelisted path as a prefix -- can scan file
                        scanFile = true;
                        break;
                    }
                }
                for (final String blacklistedPath : blacklistedPaths) {
                    if (path.startsWith(blacklistedPath)) {
                        // File path has a blacklisted path as a prefix -- don't scan it
                        scanFile = false;
                        break;
                    }
                }
                if (scanFile) {
                    // If USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is true, use zipfile entry timestamps,
                    // otherwise use the modification time of the zipfile itself. Using zipfile entry
                    // timestamps assumes that the timestamp on zipfile entries was properly added, and
                    // that the clock of the machine adding the zipfile entries is in sync with the 
                    // clock used to timestamp regular file and directory entries in the current
                    // classpath. USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is set to false by default,
                    // as zipfile entry timestamps are less trustworthy than filesystem timestamps.
                    final long entryTime = USE_ZIPFILE_ENTRY_MODIFICATION_TIMES //
                    ? entry.getTime()
                            : zipFileLastModified;
                    lastModified = Math.max(lastModified, entryTime);
                    if (entryTime > System.currentTimeMillis() && !timestampWarning) {
                        final String msg = zipfilePath + " contains modification timestamps after the current time";
                        // Log.warning(msg);
                        System.err.println(msg);
                        // Only warn once
                        timestampWarning = true;
                    }
                    if (!scanTimestampsOnly) {
                        // Match file paths against path patterns
                        for (final FilePathMatcher fileMatcher : filePathMatchers) {
                            if (fileMatcher.filePathMatches(path)) {
                                // There's a match -- open the file as a stream and call the match processor
                                try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                                    fileMatcher.processMatch(path, inputStream, (int) entry.getSize());
                                } catch (final IOException e) {
                                    if (FastClasspathScanner.verbose) {
                                        Log.log(e.getMessage() + " while processing file " + entry.getName());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File dir, final int ignorePrefixLen, boolean inWhitelistedPath,
            final boolean scanTimestampsOnly) {
        String relativePath = (ignorePrefixLen > dir.getPath().length() ? "" : dir.getPath() //
                .substring(ignorePrefixLen)) + "/";
        if (File.separatorChar != '/') {
            // Fix scanning on Windows
            relativePath = relativePath.replace(File.separatorChar, '/');
        }
        if (FastClasspathScanner.verbose) {
            Log.log("Scanning path: " + relativePath);
        }
        for (final String blacklistedPath : blacklistedPaths) {
            if (relativePath.equals(blacklistedPath)) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Reached blacklisted path: " + relativePath);
                }
                // Reached a blacklisted path -- stop scanning files and dirs
                return;
            }
        }
        boolean keepRecursing = false;
        if (!inWhitelistedPath) {
            // If not yet within a subtree of a whitelisted path, see if the current path is at least a prefix of
            // a whitelisted path, and if so, keep recursing until we hit a whitelisted path.
            for (final String whitelistedPath : whitelistedPaths) {
                if (relativePath.equals(whitelistedPath)) {
                    // Reached a whitelisted path -- can start scanning directories and files from this point
                    if (FastClasspathScanner.verbose) {
                        Log.log("Reached whitelisted path: " + relativePath);
                    }
                    inWhitelistedPath = true;
                    break;
                } else if (whitelistedPath.startsWith(relativePath) || relativePath.equals("/")) {
                    // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
                    // in case we can reach a whitelisted path.
                    keepRecursing = true;
                }
            }
        }
        if (keepRecursing || inWhitelistedPath) {
            lastModified = Math.max(lastModified, dir.lastModified());
            final File[] subFiles = dir.listFiles();
            if (subFiles != null) {
                for (final File subFile : subFiles) {
                    if (subFile.isDirectory()) {
                        // Recurse into subdirectory
                        scanDir(subFile, ignorePrefixLen, inWhitelistedPath, scanTimestampsOnly);
                    } else if (inWhitelistedPath && subFile.isFile()) {
                        // Scan file
                        scanFile(subFile,
                                relativePath.equals("/") ? subFile.getName() : relativePath + subFile.getName(),
                                scanTimestampsOnly);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If scanTimestampsOnly is true, scans the classpath for matching files, and calls any match processors if a
     * match is identified. If scanTimestampsOnly is false, only scans timestamps of files.
     */
    private void scan(final boolean scanTimestampsOnly) {
        final ArrayList<File> uniqueClasspathElements = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log("*** Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : "")
                    + " ***");
            Log.log("Classpath elements: " + uniqueClasspathElements);
            Log.log("Whitelisted paths:  " + Arrays.toString(whitelistedPaths));
            Log.log("Blacklisted paths:  " + Arrays.toString(blacklistedPaths));
        }

        // Iterate through path elements and recursively scan within each directory and zipfile
        for (int classpathEltIdx = 0; classpathEltIdx < uniqueClasspathElements.size(); classpathEltIdx++) {
            final File pathElt = uniqueClasspathElements.get(classpathEltIdx);
            final String path = pathElt.getPath();
            if (FastClasspathScanner.verbose) {
                Log.log("=> Scanning classpath element: " + path);
            }
            if (pathElt.isDirectory()) {
                // Scan within dir path element
                scanDir(pathElt, path.length() + 1, false, scanTimestampsOnly);
            } else if (pathElt.isFile()) {
                if (Utils.isJar(path)) {
                    // Scan within jar/zipfile path element
                    try (ZipFile zipfile = new ZipFile(pathElt)) {
                        scanZipfile(path, zipfile, pathElt.lastModified(), scanTimestampsOnly);
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(e.getMessage() + " while opening zipfile " + pathElt);
                        }
                    }
                } else {
                    // File listed directly on classpath
                    scanFile(pathElt, pathElt.getName(), scanTimestampsOnly);
                }
            } else if (FastClasspathScanner.verbose) {
                Log.log("Skipping non-file/non-dir on classpath: " + pathElt.getPath());
            }
        }
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "get" methods (e.g. getSubclassesOf()).
     */
    public void scan() {
        classNameToClassInfo.clear();

        // Perform the scan -- will call FileMatchProcessors for each file with a matching path.
        // Also causes RecursiveScanner.processClass() to be called for all files ending in ".class".
        scan(/* scanTimestampsOnly = */false);

        // Build class graph structure
        classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo.values());

        // Look for class, interface and annotation matches using classGraphBuilder
        for (final ClassMatcher classMatcher : classMatchers) {
            classMatcher.lookForMatches();
        }
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

    /**
     * Returns the ClassGraphBuilder created by calling .scan(), or throws RuntimeException if .scan() has not yet
     * been called.
     */
    public ClassGraphBuilder getScanResults() {
        if (classGraphBuilder == null) {
            throw new RuntimeException("Must call .scan() before attempting to get the results of the scan");
        }
        return classGraphBuilder;
    }
}
