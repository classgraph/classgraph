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
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class RecursiveScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

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
    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final File classpathElt, final String relativePath);
    }

    /**
     * A class used for associating a FilePathTester with a FileMatchProcessor.
     * 
     * filePathMatches() is separated from processMatch() to allow the caller to open a custom InputStream (zipfile
     * entry or file) after finding a matching path and before processing the contents of the stream.
     */
    public static class FilePathMatcher {
        private final FilePathTester filePathTester;
        private final FileMatchProcessorWithContext fileMatchProcessorWithContext;

        public FilePathMatcher(final FilePathTester filePathTester,
                final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessorWithContext = fileMatchProcessorWithContext;
        }

        public boolean filePathMatches(final File classpathElt, final String relativePath) {
            return filePathTester.filePathMatches(classpathElt, relativePath);
        }

        public void processMatch(final File classpathElt, final String relativePath, final InputStream inputStream,
                final int inputStreamLengthBytes) throws IOException {
            fileMatchProcessorWithContext.processMatch(classpathElt, relativePath, inputStream,
                    inputStreamLengthBytes);
        }
    }

    public void addFilePathMatcher(final FilePathMatcher filePathMatcher) {
        this.filePathMatchers.add(filePathMatcher);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a file.
     */
    private void scanFile(final File classpathElt, final File file, final String relativePath,
            final boolean scanTimestampsOnly) {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            final long startTime = System.currentTimeMillis();
            // Match file paths against path patterns
            boolean filePathMatches = false;
            for (final FilePathMatcher fileMatcher : filePathMatchers) {
                if (fileMatcher.filePathMatches(classpathElt, relativePath)) {
                    // Open the file as a stream and call the match processor
                    try (final InputStream inputStream = new FileInputStream(file)) {
                        fileMatcher.processMatch(classpathElt, relativePath, inputStream, (int) file.length());
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
    private void scanZipfile(final File classpathElt, final ZipFile zipFile, final String zipfilePath,
            final String zipInternalRootPath, final long zipFileLastModified, final boolean scanTimestampsOnly) {
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

            if (scanSpec.pathIsWhitelisted(path)) {
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
                        if (fileMatcher.filePathMatches(classpathElt, path)) {
                            // There's a match -- open the file as a stream and call the match processor
                            try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                                fileMatcher.processMatch(classpathElt, path, inputStream, (int) entry.getSize());
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
    private void scanZipfile(final File classpathElt, final String path, final String zipInternalRootPath,
            final boolean scanTimestampsOnly) {
        final String jarName = classpathElt.getName();
        if (scanSpec.jarIsWhitelisted(jarName)) {
            try (ZipFile zipfile = new ZipFile(classpathElt)) {
                scanZipfile(classpathElt, zipfile, path, zipInternalRootPath, classpathElt.lastModified(),
                        scanTimestampsOnly);
            } catch (final IOException e) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Error while opening zipfile " + classpathElt + " : " + e.toString());
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
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            boolean inWhitelistedPath, final boolean scanTimestampsOnly) {
        final String relativePath = (ignorePrefixLen > dir.getPath().length() ? "" //
                : dir.getPath().substring(ignorePrefixLen).replace(File.separatorChar, '/')) + "/";
        if (FastClasspathScanner.verbose) {
            Log.log("Scanning path: " + relativePath);
        }
        boolean keepRecursing = false;
        boolean atWhitelistedClassPackage = false;
        switch (scanSpec.pathWhitelistMatchStatus(relativePath)) {
        case WITHIN_BLACKLISTED_PATH:
            // Reached a blacklisted path -- stop scanning files and dirs
            if (FastClasspathScanner.verbose) {
                Log.log("Reached blacklisted path: " + relativePath);
            }
            return;
        case WITHIN_WHITELISTED_PATH:
            // Reached a whitelisted path -- can start scanning directories and files from this point
            if (FastClasspathScanner.verbose) {
                Log.log("Reached whitelisted path: " + relativePath);
            }
            inWhitelistedPath = true;
            break;
        case ANCESTOR_OF_WHITELISTED_PATH:
            // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
            // in case we can reach a whitelisted path.
            keepRecursing = true;
            break;
        case AT_WHITELISTED_CLASS_PACKAGE:
            // Reached a package that is itself not whitelisted, but contains a specifically-whitelisted class.
            atWhitelistedClassPackage = true;
            break;
        case NOT_WITHIN_WHITELISTED_PATH:
            break;
        default:
            throw new RuntimeException("Unknown match status");
        }
        if (keepRecursing || inWhitelistedPath || atWhitelistedClassPackage) {
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
                            if (inWhitelistedPath || keepRecursing) {
                                // Recurse into subdirectory
                                scanDir(classpathElt, subFileReal, ignorePrefixLen, inWhitelistedPath,
                                        scanTimestampsOnly);
                            }
                        } else if (subFileReal.isFile()) {
                            final String subFileName = subFileReal.getName();
                            // If in whitelisted path, or in the same non-whitelisted package as a whitelisted class
                            boolean fileIsWhitelisted = false;
                            if (inWhitelistedPath) {
                                fileIsWhitelisted = true;
                            } else if (atWhitelistedClassPackage && subFileName.endsWith(".class")) {
                                // Look for specifically-whitelisted classes in non-whitelisted packages
                                final String className = (relativePath
                                        + subFileName.substring(0, subFileName.length() - 6)).replace('/', '.');
                                fileIsWhitelisted = scanSpec.classIsWhitelisted(className);
                            }
                            if (fileIsWhitelisted) {
                                // Scan whitelisted file
                                scanFile(classpathElt, subFileReal,
                                        relativePath.equals("/") ? subFileName : relativePath + subFileName,
                                        scanTimestampsOnly);
                            }
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
        final ArrayList<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log("*** Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : "")
                    + " ***");
        }

        // Iterate through path elements and recursively scan within each directory and zipfile
        for (final File classpathElt : uniqueClasspathElts) {
            final String path = classpathElt.getPath();
            if (FastClasspathScanner.verbose) {
                Log.log("=> Scanning classpath element: " + path);
            }
            if (!classpathElt.exists()) {
                // Path element should exist (otherwise it would not have been added to the list of classpath
                // elements) unless it is a relative path within a jarfile, starting with '!'. 
                final String pathStr = classpathElt.getPath();

                final int bangPos = pathStr.indexOf('!');
                if (bangPos > 0) {
                    // If present, remove the '!' path suffix so that the .exists() test below won't fail
                    final File zipFile = Paths.get(pathStr.substring(0, bangPos)).toFile();
                    final String zipInternalRootPath = pathStr.substring(bangPos + 1) //
                            .replace(File.separatorChar, '/');
                    if (zipFile.exists()) {
                        if (ClasspathFinder.isJar(path)) {
                            if (scanSpec.scanJars) {
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

            } else if (classpathElt.isDirectory() && scanSpec.scanNonJars) {
                // Scan within directory
                scanDir(classpathElt, classpathElt, path.length() + 1, false, scanTimestampsOnly);

            } else if (classpathElt.isFile()) {
                if (ClasspathFinder.isJar(path) && scanSpec.scanJars) {
                    // Scan within jar/zipfile
                    scanZipfile(classpathElt, path, "", scanTimestampsOnly);
                } else if (scanSpec.scanNonJars) {
                    // File listed directly on classpath
                    scanFile(classpathElt, classpathElt, classpathElt.getName(), scanTimestampsOnly);
                }

            } else if (FastClasspathScanner.verbose) {
                Log.log("Skipping non-file/non-dir on classpath: " + classpathElt.getPath());
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
