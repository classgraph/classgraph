package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.RecursiveScanResult;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.KeyLocker;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.ZipFileRecycler;
import io.github.lukehutch.fastclasspathscanner.utils.ZipFileRecycler.ZipFileCacheEntry;

/**
 * A work queue that finds unique valid classpath elements. For jarfiles, looks for Class-Path manifest entries and
 * adds them to the classpath in the current order position. Calls RecursiveScanner to obtain relative paths within
 * classpath elements if enableRecursiveScanning is true.
 */
class ClasspathElementVerifierWorkQueue extends WorkQueue<ClasspathElement> {
    /**
     * Used for opening a ZipFile object and parsing its manifest, given a canonical path. Also used as a
     * placeholder for non-jar (directory) classpath entries.
     */
    static class ClasspathElementSingleton {
        final ClasspathElement classpathElt;

        /** Results of recursive scan. */
        RecursiveScanResult recursiveScanResult;

        boolean isValid;

        public ClasspathElementSingleton(final ClasspathElement classpathElt) {
            this.classpathElt = classpathElt;
        }
    }

    /** A map from canonical path to a ClasspathElementSingleton. */
    static class ClasspathElementSingletonMap extends KeyLocker {
        private final ConcurrentMap<String, ClasspathElementSingleton> map = new ConcurrentHashMap<>();

        /**
         * Initialize a ClasspathElementSingleton object for this canonical path and return it, if this is the first
         * time this canonical path has been seen, otherwise return null if there's already a singleton in the map
         * for this classpath element.
         */
        public ClasspathElementSingleton putSingleton(final String canonicalPath,
                final ClasspathElement classpathElt) {
            synchronized (getLock(canonicalPath)) {
                ClasspathElementSingleton elementSingleton = map.get(canonicalPath);
                if (elementSingleton == null) {
                    map.put(canonicalPath, elementSingleton = new ClasspathElementSingleton(classpathElt));
                    return elementSingleton;
                }
                return null;
            }
        }

        public ClasspathElementSingleton get(final String canonicalPath) {
            return map.get(canonicalPath);
        }

        public Set<Entry<String, ClasspathElementSingleton>> entrySet() {
            return map.entrySet();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private final ScanSpec scanSpec;

    /** A cache of known JRE paths. */
    private final ConcurrentHashMap<String, String> knownJREPaths = new ConcurrentHashMap<>();

    private final ClasspathElementSingletonMap canonicalPathToClasspathElementSingleton;

    private final ZipFileRecycler zipFileRecycler;

    private final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths;

    private final RecursiveScanner recursiveScanner;

    public ClasspathElementVerifierWorkQueue(final ScanSpec scanSpec,
            final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
            final ClasspathElementSingletonMap canonicalPathToClasspathElementSingleton,
            final ZipFileRecycler zipFileRecycler, final boolean enableRecursiveScanning, final ThreadLog log) {
        super(log);
        this.scanSpec = scanSpec;
        this.canonicalPathToChildCanonicalPaths = canonicalPathToChildCanonicalPaths;
        this.canonicalPathToClasspathElementSingleton = canonicalPathToClasspathElementSingleton;
        this.zipFileRecycler = zipFileRecycler;
        if (enableRecursiveScanning) {
            this.recursiveScanner = new RecursiveScanner(scanSpec, this, log);
        } else {
            this.recursiveScanner = null;
        }
    }

    @Override
    public BlockingQueue<ClasspathElement> createQueue() {
        return new LinkedBlockingQueue<>();
    }

    /** Find classpath entries. */
    @Override
    public void processWorkUnit(final ClasspathElement classpathElt) {
        // Check the classpath entry exists and is not a blacklisted system jar
        if (!classpathElt.isValid(scanSpec, knownJREPaths, log)) {
            return;
        }

        final String resolvedPath = classpathElt.getResolvedPath();
        final String canonicalPath = classpathElt.getCanonicalPath();
        if (canonicalPath == null) {
            if (FastClasspathScanner.verbose) {
                log.log("Could not canonicalize path: " + resolvedPath);
            }
            return;
        }

        // Open a single ZipFile per canonical path
        final ClasspathElementSingleton classpathElementSingleton = canonicalPathToClasspathElementSingleton
                .putSingleton(canonicalPath, classpathElt);
        if (classpathElementSingleton != null) {
            // This is the first time this canonical path has been seen on the classpath. Since the elements
            // are being processed in parallel, it may not be the earliest location in the classpath that the
            // element is listed, and classpath order matters for getting relative path masking working,
            // but this is resolved after the scan. For now, if we reach this point, the contents of this
            // directory or jarfile can be scanned without duplicating work (due to singleton initialization).
            if (FastClasspathScanner.verbose) {
                log.log("Found classpath element: " + resolvedPath);
            }

            // isValid() above determined that if this is a file, it also has a jar/zip extension
            if (classpathElt.isFile()) {
                // Open ZipFile
                final ZipFileCacheEntry zipFileCacheEntry = zipFileRecycler.get(canonicalPath);
                if (!zipFileCacheEntry.errorOpeningZipFile()) {
                    // The isValid() check already checked for system jars, but as a backup, also look for
                    // the JRE headers in the manifest file.
                    if (scanSpec.blacklistSystemJars() && zipFileCacheEntry.isSystemJar()) {
                        if (FastClasspathScanner.verbose) {
                            log.log("Skipping JRE jar: " + resolvedPath);
                        }
                        return;
                    }

                    // If the zipFile manifest has a Class-Path entry
                    final String manifestClassPath = zipFileCacheEntry.getManifestClassPathEntry();
                    if (manifestClassPath != null) {
                        if (FastClasspathScanner.verbose) {
                            log.log("Found Class-Path entry in manifest of " + resolvedPath + ": "
                                    + manifestClassPath);
                        }
                        // Get the classpath elements from the Class-Path manifest entry
                        // (these are space-delimited).
                        final String[] manifestClassPathElts = manifestClassPath.split(" ");

                        // Class-Path entries in the manifest file are resolved relative to
                        // the dir the manifest's jarfile is contaiin. Get the parent path.
                        final String pathOfContainingDir = FastPathResolver
                                .resolve(classpathElt.getFile().getParent());

                        // Enqueue child classpath elements
                        final List<String> resolvedChildPaths = new ArrayList<>(manifestClassPathElts.length);
                        for (int i = 0; i < manifestClassPathElts.length; i++) {
                            final String manifestClassPathElt = manifestClassPathElts[i];
                            final ClasspathElement linkedClasspathElt = new ClasspathElement(
                                    classpathElt.getCanonicalPath(), pathOfContainingDir, manifestClassPathElt);
                            resolvedChildPaths.add(linkedClasspathElt.getCanonicalPath());
                            // Add new work unit at head of queue
                            addWorkUnit(linkedClasspathElt);
                        }

                        // Store the ordering of the child elements relative to this canonical path
                        canonicalPathToChildCanonicalPaths.put(canonicalPath, resolvedChildPaths);
                    }
                    if (recursiveScanner != null) {
                        // Scan zipfile directory for matching relative paths
                        classpathElementSingleton.recursiveScanResult = recursiveScanner
                                .scanZipFile(classpathElt.getFile(), zipFileCacheEntry);
                    }
                    classpathElementSingleton.isValid = true;
                }
            } else {
                if (recursiveScanner != null) {
                    // Scan zipfile directory for matching relative paths
                    classpathElementSingleton.recursiveScanResult = recursiveScanner
                            .scanDir(classpathElt.getFile());
                }
                classpathElementSingleton.isValid = true;
            }
        }
    }
}
