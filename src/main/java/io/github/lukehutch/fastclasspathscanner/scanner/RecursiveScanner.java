package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FilePathTesterAndMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

class RecursiveScanner {
    /** The classpath elements. */
    private final List<File> uniqueClasspathElts;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

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

    /** The output of the recursive scan for files that matched requested criteria. */
    private final LinkedBlockingQueue<ClasspathResource> matchingFiles;

    /** The output of the recursive scan for classfiles that matched requested criteria. */
    private final LinkedBlockingQueue<ClasspathResource> matchingClassfiles;

    /** A counter for the total number of items added to the matchingClassfiles queue. */
    private final AtomicInteger matchingClassfilesCount;

    /** A map from a file to its timestamp at time of scan. */
    private final Map<File, Long> fileToTimestamp;

    private final AtomicBoolean killAllThreads;

    private final ThreadLog log;

    public RecursiveScanner(final List<File> uniqueClasspathElts, final ScanSpec scanSpec,
            final LinkedBlockingQueue<ClasspathResource> matchingFiles,
            final LinkedBlockingQueue<ClasspathResource> matchingClassfiles,
            final AtomicInteger matchingClassfilesCount, final Map<File, Long> fileToTimestamp,
            final AtomicBoolean killAllThreads, final ThreadLog log) {
        this.uniqueClasspathElts = uniqueClasspathElts;
        this.scanSpec = scanSpec;
        this.matchingFiles = matchingFiles;
        this.matchingClassfilesCount = matchingClassfilesCount;
        this.matchingClassfiles = matchingClassfiles;
        this.fileToTimestamp = fileToTimestamp;
        this.killAllThreads = killAllThreads;
        this.log = log;
    }

    // -------------------------------------------------------------------------------------------------------------

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
            log.log(2, "Scanning directory: " + dir);
        }
        final String dirPath = dir.getPath();
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        final ScanSpecPathMatch matchStatus = scanSpec.pathWhitelistMatchStatus(dirRelativePath);
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (FastClasspathScanner.verbose) {
                log.log(3, "Reached non-whitelisted (or blacklisted) directory: " + dirRelativePath);
            }
            return;
        } else if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Reached a whitelisted path -- can start scanning directories and files from this point
            inWhitelistedPath = true;
        }
        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (FastClasspathScanner.verbose) {
                log.log(3, "Invalid directory " + dir);
            }
            return;
        }

        final long startTime = System.nanoTime();
        for (final File fileInDir : filesInDir) {
            if (Thread.currentThread().isInterrupted()) {
                killAllThreads.set(true);
            }
            if (killAllThreads.get()) {
                return;
            }
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
                        log.log(3, "Reached duplicate path, ignoring: " + fileInDirRelativePath);
                    }
                    continue;
                }

                if (FastClasspathScanner.verbose) {
                    log.log(3, "Found whitelisted file: " + fileInDirRelativePath);
                }

                boolean matchedFile = false;

                // Store relative paths of any classfiles encountered
                if (fileInDirRelativePath.endsWith(".class")) {
                    matchingClassfiles.add(new ClasspathResource(classpathElt, fileInDirRelativePath));
                    matchingClassfilesCount.incrementAndGet();
                    matchedFile = true;
                }

                // Match file paths against path patterns
                for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                    if (fileMatcher.filePathTester.filePathMatches(classpathElt, fileInDirRelativePath, log)) {
                        // File's relative path matches.
                        matchingFiles.add(new ClasspathResource(classpathElt, fileInDirRelativePath,
                                fileMatcher.fileMatchProcessorWrapper));
                        matchedFile = true;
                    }
                }
                if (matchedFile) {
                    numFilesScanned.incrementAndGet();
                    fileToTimestamp.put(fileInDir, fileInDir.lastModified());
                }
            }
        }
        if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Timestamp directory too, so that added files can be detected
            fileToTimestamp.put(dir, dir.lastModified());
        }
        numDirsScanned.incrementAndGet();
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanned subdirectories of " + dir, System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File classpathElt, final ZipFile zipFile) {
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanning jarfile: " + classpathElt);
        }
        final long startTime = System.nanoTime();
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            if (Thread.currentThread().isInterrupted()) {
                killAllThreads.set(true);
            }
            if (killAllThreads.get()) {
                return;
            }
            final ZipEntry zipEntry = entries.nextElement();
            String relativePath = zipEntry.getName();
            if (relativePath.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                relativePath = relativePath.substring(1);
            }

            // Ignore directory entries, they are not needed
            final boolean isDir = zipEntry.isDirectory();
            if (isDir) {
                continue;
            }

            // Only accept first instance of a given relative path within classpath.
            if (previouslyScanned(relativePath)) {
                if (FastClasspathScanner.verbose) {
                    log.log(3, "Reached duplicate relative path, ignoring: " + relativePath);
                }
                continue;
            }

            // Get match status of the parent directory if this zipentry file's relative path
            // (or reuse the last match status for speed, if the directory name hasn't changed). 
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
            final ScanSpecPathMatch parentMatchStatus = // 
                    prevParentRelativePath == null || parentRelativePathChanged
                            ? scanSpec.pathWhitelistMatchStatus(parentRelativePath) : prevParentMatchStatus;
            prevParentRelativePath = parentRelativePath;
            prevParentMatchStatus = parentMatchStatus;

            if (parentRelativePathChanged) {
                numJarfileDirsScanned.incrementAndGet();
            }

            // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
            // that has been specifically-whitelisted
            if (parentMatchStatus != ScanSpecPathMatch.WITHIN_WHITELISTED_PATH
                    && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                            || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                continue;
            }

            if (FastClasspathScanner.verbose) {
                log.log(3, "Found whitelisted file in jarfile: " + relativePath);
            }

            boolean matchedFile = false;

            // Store relative paths of any classfiles encountered
            if (relativePath.endsWith(".class")) {
                matchingClassfiles.add(new ClasspathResource(classpathElt, relativePath));
                matchingClassfilesCount.incrementAndGet();
                matchedFile = true;
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath, log)) {
                    // File's relative path matches.
                    matchingFiles.add(new ClasspathResource(classpathElt, relativePath,
                            fileMatcher.fileMatchProcessorWrapper));
                    matchedFile = true;
                }
            }
            if (matchedFile) {
                numJarfileFilesScanned.incrementAndGet();
            }
        }
        numJarfilesScanned.incrementAndGet();
        fileToTimestamp.put(classpathElt, classpathElt.lastModified());
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    public void scan() {
        if (FastClasspathScanner.verbose) {
            for (int i = 0; i < uniqueClasspathElts.size(); i++) {
                final File elt = uniqueClasspathElts.get(i);
                log.log(1, "Classpath element " + i + ": " + elt);
            }
        }
        for (final File classpathElt : uniqueClasspathElts) {
            if (previouslyScanned(classpathElt)) {
                if (FastClasspathScanner.verbose) {
                    log.log(2, "Reached duplicate classpath entry, ignoring: " + classpathElt);
                }
                continue;
            }

            // ClasspathFinder already determined that anything that is not a directory is a jarfile
            if (classpathElt.isDirectory()) {
                // Recursively scan dir for matching paths
                scanDir(classpathElt, classpathElt, /* ignorePrefixLen = */ classpathElt.getPath().length() + 1,
                        /* inWhitelistedPath = */ false);
            } else /* is jarfile */ {
                // Scan jarfile for matching paths
                try (ZipFile zipFile = new ZipFile(classpathElt)) {
                    scanZipfile(classpathElt, zipFile);
                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log(2, "Error opening ZipFile " + classpathElt.getName() + ": " + e);
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                killAllThreads.set(true);
            }
            if (killAllThreads.get()) {
                return;
            }
        }
        if (FastClasspathScanner.verbose) {
            log.log(1, "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                    + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                    + "; jarfile-internal directories: " + numJarfileDirsScanned + "; jarfile-internal files: "
                    + numJarfileFilesScanned + "; classfiles: " + matchingClassfilesCount);
        }
    }
}
