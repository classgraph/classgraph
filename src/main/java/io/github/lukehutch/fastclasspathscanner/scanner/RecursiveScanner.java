package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FilePathTesterAndMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.ZipFileRecycler.ZipFileCacheEntry;

class RecursiveScanner {
    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    private final WorkQueue<ClasspathElement> workQueue;

    private final ThreadLog log;

    public RecursiveScanner(final ScanSpec scanSpec, final WorkQueue<ClasspathElement> workQueue,
            final ThreadLog log) {
        this.scanSpec = scanSpec;
        this.workQueue = workQueue;
        this.log = log;
    }

    // -------------------------------------------------------------------------------------------------------------

    static class RecursiveScanResult {
        List<ClasspathResource> fileMatches = new ArrayList<>();
        List<ClasspathResource> classfileMatches = new ArrayList<>();
        List<ClasspathResource> whitelistedDirectoriesAndZipfiles = new ArrayList<>();

        /**
         * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
         * earlier classpath element.
         */
        public void maskFiles(final HashSet<String> classpathRelativePathsFound) {
            final HashSet<String> maskedRelativePaths = new HashSet<>();
            for (final ClasspathResource res : classfileMatches) {
                if (!classpathRelativePathsFound.add(res.relativePath)) {
                    maskedRelativePaths.add(res.relativePath);
                }
            }
            for (final ClasspathResource res : fileMatches) {
                if (!classpathRelativePathsFound.add(res.relativePath)) {
                    maskedRelativePaths.add(res.relativePath);
                }
            }
            if (!maskedRelativePaths.isEmpty()) {
                final List<ClasspathResource> filteredClassfileMatches = new ArrayList<>();
                for (final ClasspathResource res : classfileMatches) {
                    if (!maskedRelativePaths.contains(res.relativePath)) {
                        filteredClassfileMatches.add(res);
                    }
                }
                final List<ClasspathResource> filteredFileMatches = new ArrayList<>();
                for (final ClasspathResource res : fileMatches) {
                    if (!maskedRelativePaths.contains(res.relativePath)) {
                        filteredFileMatches.add(res);
                    }
                }
                classfileMatches = filteredClassfileMatches;
                fileMatches = filteredFileMatches;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Recursively scan a directory for file path patterns matching the scan spec. */
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            boolean inWhitelistedPath, final HashSet<String> scannedCanonicalPaths,
            final RecursiveScanResult recursiveScanResult) {
        // See if this canonical path has been scanned before, so that recursive scanning doesn't get stuck in
        // an infinite loop due to symlinks
        String canonicalPath;
        try {
            canonicalPath = dir.getCanonicalPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (FastClasspathScanner.verbose) {
                    log.log(3, "Reached symlink cycle, stopping recursion: " + dir);
                }
                return;
            }
        } catch (final IOException | SecurityException e) {
            if (FastClasspathScanner.verbose) {
                log.log(3, "Could not canonicalize path: " + dir);
            }
            return;
        }
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanning directory: " + dir
                    + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));
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
            if (workQueue.interrupted()) {
                return;
            }
            if (fileInDir.isDirectory()) {
                if (inWhitelistedPath //
                        || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
                    // Recurse into subdirectory
                    scanDir(classpathElt, fileInDir, ignorePrefixLen, inWhitelistedPath, scannedCanonicalPaths,
                            recursiveScanResult);
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

                if (FastClasspathScanner.verbose) {
                    log.log(3, "Found whitelisted file: " + fileInDirRelativePath);
                }

                // Store relative paths of any classfiles encountered
                if (fileInDirRelativePath.endsWith(".class")) {
                    recursiveScanResult.classfileMatches.add(new ClasspathResource(classpathElt,
                            fileInDirRelativePath, fileInDir, fileInDir.lastModified()));
                }

                // Match file paths against path patterns
                for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                    if (fileMatcher.filePathTester.filePathMatches(classpathElt, fileInDirRelativePath, log)) {
                        // File's relative path matches.
                        recursiveScanResult.fileMatches
                                .add(new ClasspathResource(classpathElt, fileInDirRelativePath, fileInDir,
                                        fileInDir.lastModified(), fileMatcher.fileMatchProcessorWrapper));
                    }
                }
            }
        }
        if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Need to timestamp whitelisted directories too, so that added files can be detected
            recursiveScanResult.whitelistedDirectoriesAndZipfiles
                    .add(new ClasspathResource(classpathElt, canonicalPath, dir, dir.lastModified()));
        }
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanned subdirectories of " + dir, System.nanoTime() - startTime);
        }
    }

    /** Scan a directory for file path patterns matching the scan spec. */
    public RecursiveScanResult scanDir(final File dir) {
        final RecursiveScanResult recursiveScanResult = new RecursiveScanResult();
        final HashSet<String> scannedCanonicalPaths = new HashSet<>();
        scanDir(dir, dir, /* ignorePrefixLen = */ dir.getPath().length() + 1, /* inWhitelistedPath = */ false,
                scannedCanonicalPaths, recursiveScanResult);
        return recursiveScanResult;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Scan a zipfile for file path patterns matching the scan spec. */
    private void scanZipfile(final File classpathElt, final ZipFile zipFile,
            final RecursiveScanResult recursiveScanResult) {
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanning jarfile: " + classpathElt);
        }
        final long startTime = System.nanoTime();
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            if (workQueue.interrupted()) {
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

            // Store relative paths of any classfiles encountered
            if (relativePath.endsWith(".class")) {
                recursiveScanResult.classfileMatches.add(new ClasspathResource(classpathElt, relativePath,
                        /* relativePathFile = */null, zipEntry.getTime()));
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath, log)) {
                    // File's relative path matches.
                    // Don't use the last modified time from the individual zipEntry objects,
                    // we use the last modified time for the zipfile itself instead.
                    recursiveScanResult.classfileMatches
                            .add(new ClasspathResource(classpathElt, relativePath, /* relativePathFile = */null,
                                    /* lastModifiedTime = */0L, fileMatcher.fileMatchProcessorWrapper));
                }
            }
        }
        recursiveScanResult.whitelistedDirectoriesAndZipfiles.add(new ClasspathResource(classpathElt,
                classpathElt.getPath(), classpathElt, classpathElt.lastModified()));
        if (FastClasspathScanner.verbose) {
            log.log(2, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
        }
    }

    /** Scan a zipfile for file path patterns matching the scan spec. */
    public RecursiveScanResult scanZipFile(final File file, final ZipFileCacheEntry zipFileCacheEntry) {
        final RecursiveScanResult recursiveScanResult = new RecursiveScanResult();
        ZipFile zipFile = null;
        try {
            zipFile = zipFileCacheEntry.acquire();
            scanZipfile(file, zipFile, recursiveScanResult);
        } finally {
            zipFileCacheEntry.release(zipFile);
        }
        return recursiveScanResult;
    }
}
