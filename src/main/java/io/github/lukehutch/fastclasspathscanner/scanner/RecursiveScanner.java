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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

public class RecursiveScanner {
    /** Whether to scan jarfiles. */
    private boolean scanJars = true;

    /** Whether to scan classpath entries that are not jarfiles (i.e. that are directories or files). */
    private boolean scanNonJars = true;

    /** List of jars to scan. */
    private final HashSet<String> whitelistedJars = new HashSet<>();

    /** List of jars to not scan. */
    private final HashSet<String> blacklistedJars = new HashSet<>();

    /** List of directory path prefixes to scan. */
    private final String[] whitelistedPaths;

    /** List of directory path prefixes to not scan. */
    private final String[] blacklistedPaths;

    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given
     * regexp.
     */
    private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    /**
     * If this is set to true, then the timestamps of zipfile entries should be used to determine when files inside
     * a zipfile have changed; if set to false, then the timestamp of the zipfile itself is used. Itis recommended
     * to leave this set to false, since zipfile timestamps are less trustworthy than filesystem timestamps.
     */
    private static final boolean USE_ZIPFILE_ENTRY_MODIFICATION_TIMES = false;

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
    public RecursiveScanner(final ClasspathFinder classpathFinder, final String[] scanSpecs) {
        this.classpathFinder = classpathFinder;
        final HashSet<String> uniqueWhitelistedPaths = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPaths = new HashSet<>();
        for (final String scanSpecEntry : scanSpecs) {
            String spec = scanSpecEntry;
            final boolean blacklisted = spec.startsWith("-");
            if (blacklisted) {
                // Strip off "-"
                spec = spec.substring(1);
            }
            final boolean isJar = spec.startsWith("jar:");
            if (isJar) {
                // Strip off "jar:"
                spec = spec.substring(4);
                if (spec.isEmpty()) {
                    if (blacklisted) {
                        // Specifying "-jar:" blacklists all jars for scanning
                        scanJars = false;
                    } else {
                        // Specifying "jar:" causes only jarfiles to be scanned, while whitelisting all jarfiles
                        scanNonJars = false;
                    }
                } else {
                    if (blacklisted) {
                        blacklistedJars.add(spec);
                    } else {
                        whitelistedJars.add(spec);
                    }
                }
            } else {
                // Convert package name to path prefix
                spec = spec.replace('.', '/') + "/";
                if (blacklisted) {
                    if (spec.equals("/") || spec.isEmpty()) {
                        Log.log("Ignoring blacklist of root package, it would prevent all scanning");
                    } else {
                        uniqueBlacklistedPaths.add(spec);
                    }
                } else {
                    uniqueWhitelistedPaths.add(spec);
                }
            }
        }
        uniqueWhitelistedPaths.removeAll(uniqueBlacklistedPaths);
        whitelistedJars.removeAll(blacklistedJars);
        if (!whitelistedJars.isEmpty()) {
            // Specifying "jar:somejar.jar" causes only the specified jarfile to be scanned
            scanNonJars = false;
        }
        if (uniqueWhitelistedPaths.isEmpty() || uniqueWhitelistedPaths.contains("/")) {
            // Scan all packages
            whitelistedPaths = new String[] { "/" };
        } else {
            // Scan whitelisted packages minus blacklisted sub-packages
            whitelistedPaths = new String[uniqueWhitelistedPaths.size()];
            int i = 0;
            for (final String path : uniqueWhitelistedPaths) {
                whitelistedPaths[i++] = path;
            }
        }
        blacklistedPaths = new String[uniqueBlacklistedPaths.size()];
        int i = 0;
        for (final String path : uniqueBlacklistedPaths) {
            blacklistedPaths[i++] = path;
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a file.
     */
    private void scanFile(final File file, final String relativePath, final boolean scanTimestampsOnly) {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            final long startTime = System.currentTimeMillis();
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
                Log.log("Scanned file " + relativePath + " in " + (System.currentTimeMillis() - startTime)
                        + " msec");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns. (Does not recurse into zipfiles within zipfiles.)
     */
    private void scanZipfile(final String zipfilePath, final String zipInternalRootPath, final ZipFile zipFile,
            final long zipFileLastModified, final boolean scanTimestampsOnly) {
        if (FastClasspathScanner.verbose) {
            Log.log("Scanning jarfile: " + zipfilePath + (zipInternalRootPath.isEmpty() ? ""
                    : " ; classpath root within jarfile: " + zipInternalRootPath));
        }
        final long startTime = System.currentTimeMillis();
        boolean timestampWarning = false;

        // Find the root prefix, which is "" in the case of a jarfile listed as normal on the classpath,
        // but will be "root/prefix" in the case of an entry "jar:/path/file.jar!/root/prefix". This can be
        // used by some classloaders to specify a resource root inside a jarfile, e.g. "META-INF/classfiles".
        String rootPrefix = zipInternalRootPath;
        if (rootPrefix.startsWith("/")) {
            rootPrefix = rootPrefix.substring(1);
        }
        if (!rootPrefix.isEmpty() && !rootPrefix.endsWith("/")) {
            rootPrefix = rootPrefix + "/";
        }
        final int rootPrefixLen = rootPrefix.length();

        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Scan for matching filenames
            final ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }

            // Only process file entries (zipfile indices contain both directory entries and
            // separate file entries for files within each directory, in lexicographic order)
            String path = entry.getName();
            if (path.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                path = path.substring(1);
            }
            // Check if this zip entry is within the classfile root, if specified using
            // "jar:/.../file.jar!/path" syntax (rootPrefix is "" if there is no zip-internal root path )
            if (rootPrefixLen > 0) {
                if (!path.startsWith(rootPrefix)) {
                    // Not a child of rootPrefix, skip entry
                    continue;
                }
                // Strip off rootPrefix to relativize path
                path = path.substring(rootPrefixLen);
            }

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
                        ? entry.getTime() : zipFileLastModified;
                lastModified = Math.max(lastModified, entryTime);
                if (entryTime > System.currentTimeMillis() && !timestampWarning) {
                    Log.log(zipfilePath + " contains modification timestamps after the current time");
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
        if (FastClasspathScanner.verbose) {
            Log.log("Scanned jarfile " + zipfilePath + " in " + (System.currentTimeMillis() - startTime) + " msec");
        }
    }

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File pathElt, final String path, final String zipInternalRootPath,
            final boolean scanTimestampsOnly) {
        final String jarName = pathElt.getName();
        if ((whitelistedJars.isEmpty() || whitelistedJars.contains(jarName))
                && !blacklistedJars.contains(jarName)) {
            try (ZipFile zipfile = new ZipFile(pathElt)) {
                scanZipfile(path, zipInternalRootPath, zipfile, pathElt.lastModified(), scanTimestampsOnly);
            } catch (final IOException e) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Error while opening zipfile " + pathElt + " : " + e.toString());
                }
            }
        } else {
            if (FastClasspathScanner.verbose) {
                Log.log("Jarfile did not match whitelist/blacklist criteria: " + jarName);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File dir, final int ignorePrefixLen, boolean inWhitelistedPath,
            final boolean scanTimestampsOnly) {
        final String relativePath = (ignorePrefixLen > dir.getPath().length() ? "" //
                : dir.getPath().substring(ignorePrefixLen).replace(File.separatorChar, '/')) + "/";
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
                    File subFileReal = null;
                    try {
                        // N.B. we need NOFOLLOW_LINKS, because otherwise resolved paths may no longer appear
                        // as a child of a classpath element, and/or the path tree may no longer conform to
                        // the package tree. 
                        subFileReal = subFile.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toFile();
                    } catch (IOException | SecurityException e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log("Could not access file " + subFile + ": " + e.getMessage());
                        }
                    }
                    if (subFileReal != null) {
                        if (subFileReal.isDirectory()) {
                            // Recurse into subdirectory
                            scanDir(subFileReal, ignorePrefixLen, inWhitelistedPath, scanTimestampsOnly);
                        } else if (inWhitelistedPath && subFileReal.isFile()) {
                            // Scan file
                            scanFile(subFileReal, relativePath.equals("/") ? subFileReal.getName()
                                    : relativePath + subFileReal.getName(), scanTimestampsOnly);
                        }
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
        for (final File pathElt : uniqueClasspathElements) {
            final String path = pathElt.getPath();
            if (FastClasspathScanner.verbose) {
                Log.log("=> Scanning classpath element: " + path);
            }
            if (!pathElt.exists()) {
                // Path element should exist (otherwise it would not have been added to the list of classpath
                // elements) unless it is a relative path within a jarfile, starting with '!'. 
                final String pathStr = pathElt.getPath();

                final int bangPos = pathStr.indexOf('!');
                if (bangPos > 0) {
                    // If present, remove the '!' path suffix so that the .exists() test below won't fail
                    final File zipFile = Paths.get(pathStr.substring(0, bangPos)).toFile();
                    final String zipInternalRootPath = pathStr.substring(bangPos + 1) //
                            .replace(File.separatorChar, '/');
                    if (zipFile.exists()) {
                        if (Utils.isJar(path)) {
                            if (scanJars) {
                                // Scan within jar/zipfile
                                scanZipfile(zipFile, path, zipInternalRootPath, scanTimestampsOnly);
                            }
                        } else {
                            if (FastClasspathScanner.verbose) {
                                Log.log("Not a jarfile, but illegal '!' character in classpath entry: " + pathStr);
                            }
                        }
                    } else {
                        if (FastClasspathScanner.verbose) {
                            // Should only happen if something is deleted from classpath during scanning
                            Log.log("Jarfile on classpath no longer exists: " + zipFile);
                        }
                    }
                } else {
                    if (FastClasspathScanner.verbose) {
                        // Should only happen if something is deleted from classpath during scanning
                        Log.log("Classpath element no longer exists: " + path);
                    }
                }

            } else if (pathElt.isDirectory() && scanNonJars) {
                // Scan within directory
                scanDir(pathElt, path.length() + 1, false, scanTimestampsOnly);

            } else if (pathElt.isFile()) {
                if (Utils.isJar(path) && scanJars) {
                    // Scan within jar/zipfile
                    scanZipfile(pathElt, path, "", scanTimestampsOnly);
                } else if (scanNonJars) {
                    // File listed directly on classpath
                    scanFile(pathElt, pathElt.getName(), scanTimestampsOnly);
                }

            } else if (FastClasspathScanner.verbose) {
                Log.log("Skipping non-file/non-dir on classpath: " + pathElt.getPath());
            }
        }
    }

    /** Scans the classpath for matching files, and calls any FileMatchProcessors if a match is identified. */
    public void scan() {
        scan(/* scanTimestampsOnly = */false);
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
