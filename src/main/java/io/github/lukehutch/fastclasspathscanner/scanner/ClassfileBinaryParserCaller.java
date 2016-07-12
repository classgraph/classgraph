package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.Log.DeferredLog;

/**
 * Class for calling ClassfileBinaryParser in parallel for classfile resources. Consumes ClassfileResource objects
 * from the classfileResourcesIn queue and produces ClassInfoUnlinked objects, placing them in the
 * classInfoUnlinkedOut queue.
 */
class ClassfileBinaryParserCaller implements Callable<Void> {
    private final LinkedBlockingQueue<ClassfileResource> classfileResourcesIn;
    private final Queue<ClassInfoUnlinked> classInfoUnlinkedOut;
    private final ScanSpec scanSpec;
    private final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch;
    private final ConcurrentHashMap<String, String> stringInternMap;
    private final InterruptionChecker interruptionChecker;
    private final DeferredLog log;

    public ClassfileBinaryParserCaller(final LinkedBlockingQueue<ClassfileResource> classfileResourcesIn,
            final Queue<ClassInfoUnlinked> classInfoUnlinkedOut, final ScanSpec scanSpec,
            final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch,
            final ConcurrentHashMap<String, String> stringInternMap, InterruptionChecker interruptionChecker,
            final DeferredLog log) {
        this.classfileResourcesIn = classfileResourcesIn;
        this.classInfoUnlinkedOut = classInfoUnlinkedOut;
        this.scanSpec = scanSpec;
        this.classNameToStaticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch;
        this.stringInternMap = stringInternMap;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
    }

    @Override
    public Void call() {
        ZipFile currentlyOpenZipFile = null;
        try {
            ClassfileResource prevClassfileResource = null;
            // Reuse one ClassfileBinaryParser for all classfiles parsed by a given thread, to avoid
            // the overhead of re-allocating buffers between classfiles.
            // Each call to classfileBinaryParser.readClassInfoFromClassfileHeader() resets the parser.
            final ClassfileBinaryParser classfileBinaryParser = new ClassfileBinaryParser(scanSpec,
                    interruptionChecker, log);
            for (;;) {
                ClassfileResource classfileResource = classfileResourcesIn.take();
                if (classfileResource == ClassfileResource.END_OF_QUEUE) {
                    // Received poison pill -- no more work to do. Send poison pill to next stage too.
                    classInfoUnlinkedOut.add(ClassInfoUnlinked.END_OF_QUEUE);
                    return null;
                }
                interruptionChecker.check();
                final long fileStartTime = System.nanoTime();
                final boolean classfileResourceIsJar = classfileResource.classpathElt.isFile();
                // Compare classpath element of current resource to that of previous resource
                if (prevClassfileResource == null
                        || classfileResource.classpathElt != prevClassfileResource.classpathElt) {
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
                    if (classfileResourceIsJar) {
                        // Open a new zipfile, if new resource is a jar
                        try {
                            currentlyOpenZipFile = new ZipFile(classfileResource.classpathElt);
                        } catch (final IOException e) {
                            if (FastClasspathScanner.verbose) {
                                log.log(2, "Exception while trying to open " + classfileResource.classpathElt + ": "
                                        + e);
                            }
                            continue;
                        }
                    }
                }
                // Get input stream from classpath element and relative path
                try (InputStream inputStream = classfileResourceIsJar
                        ? currentlyOpenZipFile
                                .getInputStream(currentlyOpenZipFile.getEntry(classfileResource.relativePath))
                        : new FileInputStream(classfileResource.classpathElt.getPath() + File.separatorChar
                                + (File.separatorChar == '/' ? classfileResource.relativePath
                                        : classfileResource.relativePath.replace('/', File.separatorChar)))) {
                    interruptionChecker.check();
                    // Parse classpath binary format, creating a ClassInfoUnlinked object
                    final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                            .readClassInfoFromClassfileHeader(inputStream, classfileResource.relativePath,
                                    classNameToStaticFinalFieldsToMatch, stringInternMap);
                    // If class was successfully read, add new ClassInfoUnlinked object to output queue
                    if (thisClassInfoUnlinked != null) {
                        classInfoUnlinkedOut.add(thisClassInfoUnlinked);
                        // Log info about class
                        thisClassInfoUnlinked.logClassInfo(log);
                    }

                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log(2, "Exception while trying to open " + classfileResource.relativePath + ": " + e);
                    }
                }
                if (FastClasspathScanner.verbose) {
                    log.log(3, "Parsed classfile " + classfileResource.relativePath + " on classpath element "
                            + classfileResource.classpathElt, System.nanoTime() - fileStartTime);
                }
                prevClassfileResource = classfileResource;
            }
        } catch (InterruptedException e) {
            interruptionChecker.interrupt();
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
        return null;
    }
}
