package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

public class ZipFileRecycler extends KeyLocker implements AutoCloseable {
    private final ConcurrentMap<String, ZipFileCacheEntry> canonicalPathToCacheEntry = new ConcurrentHashMap<>();
    private final ThreadLog log;

    public ZipFileRecycler(final ThreadLog log) {
        this.log = log;
    }

    public class ZipFileCacheEntry extends Recycler<ZipFile> {
        public String canonicalPath;

        /** All ZipFile instances that have been opened for this zipfile. */
        public ArrayList<ZipFile> openZipFileInstances = new ArrayList<>();

        /** ZipFiles that are currently unused by any worker thread. */
        public ArrayList<ZipFile> unusedOpenZipFileInstances = new ArrayList<>();

        private final AtomicBoolean manifestLoaded = new AtomicBoolean(false);

        /** The Class-Path entry in a jar's manifest, if present. */
        private String classPathManifestEntry;

        /** True if a jar's manifest indicates it is a system jar (part of the JRE). */
        private boolean isSystemJar;

        private boolean errorOpeningZipFile;

        public ZipFileCacheEntry(final String canonicalPath) {
            this.canonicalPath = canonicalPath;
        }

        @Override
        public ZipFile newInstance() {
            try {
                final ZipFile zipFile = new ZipFile(canonicalPath);
                loadManifest(zipFile);
                return zipFile;
            } catch (final IOException e) {
                errorOpeningZipFile = true;
                if (FastClasspathScanner.verbose) {
                    log.log("Exception opening zipfile " + canonicalPath, e);
                }
                return null;
            }
        }

        private void loadManifest(final ZipFile zipFile) {
            // Only parse the manifest the first time the zipfile is opened
            if (!manifestLoaded.getAndSet(true)) {
                // Parse the manifest, if present
                final FastManifestParser manifestParser = new FastManifestParser(zipFile, log);
                isSystemJar = manifestParser.isSystemJar;
                classPathManifestEntry = manifestParser.classPath;
            }
        }

        private void loadManifest() {
            if (!manifestLoaded.get()) {
                ZipFile zipFile = null;
                try {
                    zipFile = acquire();
                    loadManifest(zipFile);
                } finally {
                    release(zipFile);
                    zipFile = null;
                }
            }
        }

        public String getManifestClassPathEntry() {
            loadManifest();
            return classPathManifestEntry;
        }

        public boolean isSystemJar() {
            loadManifest();
            return isSystemJar;
        }

        public boolean errorOpeningZipFile() {
            loadManifest();
            return errorOpeningZipFile;
        }
    }

    /** Get the ZipFileCacheEntry for the given canonical path. */
    public ZipFileCacheEntry get(final String canonicalPath) {
        synchronized (getLock(canonicalPath)) {
            ZipFileCacheEntry zipFileCacheEntry = canonicalPathToCacheEntry.get(canonicalPath);
            if (zipFileCacheEntry == null) {
                canonicalPathToCacheEntry.put(canonicalPath,
                        zipFileCacheEntry = this.new ZipFileCacheEntry(canonicalPath));
            }
            return zipFileCacheEntry;
        }
    }

    /** Call this only after all workers have shut down. */
    @Override
    public void close() {
        for (final ZipFileCacheEntry cacheEntry : canonicalPathToCacheEntry.values()) {
            try {
                cacheEntry.close();
            } catch (final Exception e) {
                if (FastClasspathScanner.verbose) {
                    log.log("Exception closing zipfile " + cacheEntry.canonicalPath, e);
                }
            }
        }
    }
}
