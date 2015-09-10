package io.github.lukehutch.fastclasspathscanner.scanner;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RecursiveScanner {
    private final ClasspathFinder classpath;

    private final ClassGraphBuilder classGraphBuilder;

    /**
     * List of directory path prefixes to scan (produced from list of package prefixes passed into the constructor)
     */
    private final String[] whitelistedPaths, blacklistedPaths;

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given
     * regexp.
     */
    private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

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

    public RecursiveScanner(final ClasspathFinder classpath, final String[] whitelistedPaths,
            final String[] blacklistedPaths, //
            final ClassGraphBuilder classGraphBuilder) {
        this.classpath = classpath;
        this.whitelistedPaths = whitelistedPaths;
        this.blacklistedPaths = blacklistedPaths;
        this.classGraphBuilder = classGraphBuilder;
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

    /** An interface used for testing if a class matches specified criteria. */
    public static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    public void addClassMatcher(final ClassMatcher classMatcher) {
        this.classMatchers.add(classMatcher);
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
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "get" methods (e.g. getSubclassesOf()).
     */
    public void scan(final boolean scanTimestampsOnly) {
        final ArrayList<File> uniqueClasspathElements = classpath.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log("*** Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : "")
                    + " ***");
            Log.log("Classpath elements: " + uniqueClasspathElements);
            Log.log("Whitelisted paths:  " + Arrays.toString(whitelistedPaths));
            Log.log("Blacklisted paths:  " + Arrays.toString(blacklistedPaths));
        }

        final long scanStart = System.currentTimeMillis();

        if (!scanTimestampsOnly) {
            classGraphBuilder.reset();
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

        if (!scanTimestampsOnly) {
            // Look for class, interface and annotation matches
            for (final ClassMatcher classMatcher : classMatchers) {
                classMatcher.lookForMatches();
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log("*** Scanning took: " + (System.currentTimeMillis() - scanStart) + " ms ***");
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
}
