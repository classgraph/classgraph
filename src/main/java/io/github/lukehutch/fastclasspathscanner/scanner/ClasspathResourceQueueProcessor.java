package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

class ClasspathResourceQueueProcessor {
    public interface ClasspathResourceProcessor {
        public void processClasspathResource(ClasspathResource classpathResource, InputStream inputStream,
                long inputStreamLength) throws IOException, InterruptedException;
    }

    public static void processClasspathResourceQueue(final LinkedBlockingQueue<ClasspathResource> queue,
            final ClasspathResource endOfQueueMarker, final ClasspathResourceProcessor classpathResourceProcessor,
            final ThreadLog log) throws InterruptedException {
        ZipFile currentlyOpenZipFile = null;
        try {
            ClasspathResource prevClasspathResource = null;
            for (;;) {
                final ClasspathResource classpathResource = queue.take();
                if (classpathResource == endOfQueueMarker) {
                    // Received poison pill -- no more work to do.
                    return;
                }
                final long fileStartTime = System.nanoTime();
                final boolean classpathResourceIsJar = classpathResource.classpathElt.isFile();
                // Compare classpath element of current resource to that of previous resource
                if (prevClasspathResource == null
                        || classpathResource.classpathElt != prevClasspathResource.classpathElt) {
                    // Classpath element has changed
                    if (currentlyOpenZipFile != null) {
                        // Close previous zipfile, if one is open
                        try {
                            currentlyOpenZipFile.close();
                        } catch (final IOException e) {
                            // Ignore
                        }
                        currentlyOpenZipFile = null;
                    }
                    if (classpathResourceIsJar) {
                        // Open a new zipfile, if new resource is a jar
                        try {
                            currentlyOpenZipFile = new ZipFile(classpathResource.classpathElt);
                        } catch (final IOException e) {
                            if (FastClasspathScanner.verbose) {
                                log.log(2, "Exception while trying to open jarfile "
                                        + classpathResource.classpathElt + ": " + e);
                            }
                            continue;
                        }
                    }
                }
                // Get input stream from classpath element and relative path
                if (classpathResourceIsJar) {
                    final ZipEntry zipEntry = currentlyOpenZipFile.getEntry(classpathResource.relativePath);
                    try (InputStream inputStream = currentlyOpenZipFile
                            .getInputStream(currentlyOpenZipFile.getEntry(classpathResource.relativePath))) {
                        // Process classpath resource
                        classpathResourceProcessor.processClasspathResource(classpathResource, inputStream,
                                zipEntry.getSize());
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            log.log(2, "Exception while trying to open entry " + classpathResource.relativePath
                                    + " in jarfile " + classpathResource.classpathElt + " : " + e);
                        }
                    }
                } else {
                    final String filename = classpathResource.classpathElt.getPath() + File.separatorChar
                            + (File.separatorChar == '/' ? classpathResource.relativePath
                                    : classpathResource.relativePath.replace('/', File.separatorChar));
                    final File file = new File(filename);
                    try (InputStream inputStream = new FileInputStream(file)) {
                        // Process classpath resource
                        classpathResourceProcessor.processClasspathResource(classpathResource, inputStream,
                                file.length());
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            log.log(2, "Exception while trying to open file " + filename + " : " + e);
                        }
                    }
                }
                if (FastClasspathScanner.verbose) {
                    log.log(3, "Parsed classfile " + classpathResource.relativePath + " on classpath element "
                            + classpathResource.classpathElt, System.nanoTime() - fileStartTime);
                }
                prevClasspathResource = classpathResource;

                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (currentlyOpenZipFile != null) {
                // Close last zipfile
                try {
                    currentlyOpenZipFile.close();
                } catch (final IOException e) {
                    // Ignore
                }
                currentlyOpenZipFile = null;
            }
        }
    }
}
