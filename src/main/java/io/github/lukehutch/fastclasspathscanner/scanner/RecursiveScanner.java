package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FilePathTesterAndMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Log.DeferredLog;

public class RecursiveScanner implements Callable<Void> {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

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

    /** The total number of classfiles scanned. */
    private final AtomicInteger numClassfilesScanned = new AtomicInteger();

    /** The output of the recursive scan for files that matched requested criteria. */
    private final LinkedBlockingQueue<ClasspathResource> matchingFiles;

    /** The output of the recursive scan for classfiles that matched requested criteria. */
    private final LinkedBlockingQueue<ClasspathResource> matchingClassfiles;

    /** A map from a file to its timestamp at time of scan. */
    private final Map<File, Long> fileToTimestamp;

    /**
     * The number of worker threads. This class runs in a single thread, but it needs to place this many poison
     * pills in the matchingClassfiles queue at the end of the scan.
     */
    private final int numWorkerThreads;

    /** The thread-local log. */
    private final DeferredLog log;

    private boolean interrupted = false;

    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec,
            final LinkedBlockingQueue<ClasspathResource> matchingFiles,
            final LinkedBlockingQueue<ClasspathResource> matchingClassfiles, final Map<File, Long> fileToTimestamp,
            final int numWorkerThreads, final DeferredLog log) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
        this.matchingFiles = matchingFiles;
        this.matchingClassfiles = matchingClassfiles;
        this.fileToTimestamp = fileToTimestamp;
        this.numWorkerThreads = numWorkerThreads;
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
            log.log(3, "Scanning directory: " + dir);
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
                log.log(4, "Invalid directory " + dir);
            }
            return;
        }

        final long startTime = System.nanoTime();
        for (final File fileInDir : filesInDir) {
            if (interrupted |= Thread.currentThread().isInterrupted()) {
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
                    matchingClassfiles.add(new ClasspathResource(classpathElt, /* classpathEltIsJar = */ false,
                            fileInDirRelativePath));
                    numClassfilesScanned.incrementAndGet();
                    matchedFile = true;
                }

                // Match file paths against path patterns
                for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                    if (fileMatcher.filePathTester.filePathMatches(classpathElt, fileInDirRelativePath)) {
                        // File's relative path matches.
                        matchingFiles.add(new ClasspathResource(classpathElt, /* classpathEltIsJar = */ false,
                                fileInDirRelativePath, fileMatcher.fileMatchProcessorWrapper));
                        matchedFile = true;
                    }
                }
                if (matchedFile) {
                    numFilesScanned.incrementAndGet();
                    fileToTimestamp.put(fileInDir, fileInDir.lastModified());
                }
            }
        }
        numDirsScanned.incrementAndGet();
        if (FastClasspathScanner.verbose) {
            log.log(3, "Scanned directory " + dir + " and subdirectories", System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File classpathElt, final ZipFile zipFile) {
        if (FastClasspathScanner.verbose) {
            log.log(3, "Scanning jarfile: " + classpathElt);
        }
        final long startTime = System.nanoTime();
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            if (interrupted |= Thread.currentThread().isInterrupted()) {
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
                matchingClassfiles
                        .add(new ClasspathResource(classpathElt, /* classpathEltIsJar = */ true, relativePath));
                numClassfilesScanned.incrementAndGet();
                matchedFile = true;
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            scanSpec.getFilePathTestersAndMatchProcessorWrappers()) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath)) {
                    // File's relative path matches.
                    matchingFiles.add(new ClasspathResource(classpathElt, /* classpathEltIsJar = */ true,
                            relativePath, fileMatcher.fileMatchProcessorWrapper));
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
            log.log(4, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public Void call() throws Exception {
        final List<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            log.log(1, "Classpath element scan order:");
            for (int i = 0; i < uniqueClasspathElts.size(); i++) {
                final File elt = uniqueClasspathElts.get(i);
                log.log(2, i + ": " + elt);
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
            final boolean isDirectory = classpathElt.isDirectory(), isJar = !isDirectory;

            if (isDirectory && scanSpec.scanNonJars) {
                // Recursively scan dir for matching paths
                scanDir(classpathElt, classpathElt, /* ignorePrefixLen = */ classpathElt.getPath().length() + 1,
                        /* inWhitelistedPath = */ false);

            } else if (isJar && scanSpec.scanJars) {
                // Scan jarfile for matching paths
                if (!scanSpec.jarIsWhitelisted(classpathElt.getName())) {
                    if (FastClasspathScanner.verbose) {
                        log.log(2, "Skipping jarfile that did not match whitelist/blacklist criteria: "
                                + classpathElt.getName());
                    }
                    continue;
                }
                try (ZipFile zipFile = new ZipFile(classpathElt)) {
                    scanZipfile(classpathElt, zipFile);
                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log(2, "Error opening ZipFile " + classpathElt.getName() + ": " + e);
                    }
                }

            } else {
                if (FastClasspathScanner.verbose) {
                    log.log(2, "Skipping classpath element " + classpathElt.getPath());
                }
            }
            if (interrupted |= Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        // Place numWorkerThreads poison pills at end of work queues
        for (int i = 0; i < numWorkerThreads; i++) {
            matchingClassfiles.add(ClasspathResource.END_OF_QUEUE);
            matchingFiles.add(ClasspathResource.END_OF_QUEUE);
        }
        if (FastClasspathScanner.verbose) {
            Log.log(1, "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                    + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                    + "; jarfile-internal directories: " + numJarfileDirsScanned + "; jarfile-internal files: "
                    + numJarfileFilesScanned + "; classfiles: " + numClassfilesScanned);
        }
        return null;
    }
}
