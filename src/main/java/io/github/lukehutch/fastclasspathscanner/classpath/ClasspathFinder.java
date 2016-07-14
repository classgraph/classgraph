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
package io.github.lukehutch.fastclasspathscanner.classpath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.Manifest;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.Join;
import io.github.lukehutch.fastclasspathscanner.utils.ThreadLog;

public class ClasspathFinder {
    /** The scanning specification. */
    private final ScanSpec scanSpec;

    /** The unique elements of the classpath, as an ordered list. */
    private final ArrayList<File> classpathElements = new ArrayList<>();

    /** The unique elements of the classpath, as a set. */
    private final HashSet<String> classpathElementsSet = new HashSet<>();

    /** The set of JRE paths found so far in the classpath, cached for speed. */
    private final HashSet<String> knownJREPaths = new HashSet<>();

    /** Manually-registered ClassLoaderHandlers. */
    private final HashSet<ClassLoaderHandler> extraClassLoaderHandlers = new HashSet<>();

    /** Whether or not classpath has been read (supporting lazy reading of classpath). */
    private boolean initialized = false;

    private ThreadLog log;

    public ClasspathFinder(final ScanSpec scanSpec, ThreadLog log) {
        this.scanSpec = scanSpec;
        this.log = log;
    }

    /** Clear the classpath. */
    private void clearClasspath() {
        classpathElements.clear();
        classpathElementsSet.clear();
        initialized = false;
    }

    private static boolean isJarMatchCase(final String path) {
        return path.endsWith(".jar") || path.endsWith(".zip") || path.endsWith(".war") || path.endsWith(".car");
    }

    /** Returns true if the path ends with a JAR extension */
    public static boolean isJar(final String path) {
        return isJarMatchCase(path) || isJarMatchCase(path.toLowerCase());
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions. Follows symbolic links, and resolves any relative paths relative to
     * resolveBaseFile.
     */
    private File urlToFile(final File resolveBaseFile, final String relativePathStr) {
        if (relativePathStr.isEmpty()) {
            return null;
        }
        String pathStr = relativePathStr;
        // Ignore "jar:", we look for ".jar" on the end of filenames instead
        if (pathStr.startsWith("jar:")) {
            pathStr = pathStr.substring(4);
        }
        // We don't fetch remote classpath entries, although they are theoretically valid if using a URLClassLoader
        if (pathStr.startsWith("http:") || pathStr.startsWith("https:")) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring remote entry in classpath: " + pathStr);
            }
            return null;
        }
        // Turn any backslash separators into forward slashes since we want to end up with a URL
        if (pathStr.indexOf('\\') >= 0) {
            pathStr = pathStr.replace('\\', '/');
        }
        // Strip off any "file:" prefix from relative path
        if (pathStr.startsWith("file://")) {
            pathStr = pathStr.substring(7);
        }
        if (pathStr.startsWith("file:")) {
            pathStr = pathStr.substring(5);
        }
        // Handle windows drive designations by turning them into an absolute URL, if they're not already
        if (pathStr.length() > 2 && Character.isLetter(pathStr.charAt(0)) && pathStr.charAt(1) == ':') {
            pathStr = '/' + pathStr;
        }
        // Remove any trailing "/"
        if (pathStr.endsWith("/") && !pathStr.equals("/")) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        // Replace any "//" with "/"
        pathStr = pathStr.replace("//", "/");
        // Replace any spaces with %20
        pathStr = pathStr.replace(" ", "%20");
        try {
            if (pathStr.startsWith("/")) {
                // If path is an absolute path, ignore the base path.
                // Need to deal with possibly-broken mixes of file:// URLs and system-dependent path formats -- see:
                // https://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html
                // http://stackoverflow.com/a/17870390/3950982
                // i.e. the recommended way to do this is URL -> URI -> Path, especially to handle weirdness on
                // Windows. However, we skip the last step, because Path is slow.
                return new File(new URL("file:" + pathStr).toURI());
            } else if (resolveBaseFile == null) {
                // No base provided
                return new File(new URL(pathStr).toURI());
            } else {
                // If path is a relative path, resolve it relative to the base path
                String base = resolveBaseFile.toURI().toString();
                if (!base.endsWith("/")) {
                    base += "/";
                }
                return new File(new URL(base + pathStr).toURI());
            }
        } catch (MalformedURLException | URISyntaxException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Exception while constructing classpath entry from base file " + resolveBaseFile
                        + " and relative path " + relativePathStr + ": " + e);
            }
            return null;
        }
    }

    /** Add a classpath element relative to a base file. */
    public void addClasspathElement(final String pathElement) {
        addClasspathElement(null, pathElement);
    }

    /** Add a classpath element. */
    public void addClasspathElement(final File baseFile, final String pathElement) {
        final File pathFile = urlToFile(baseFile, pathElement);
        if (pathFile == null) {
            return;
        }
        if (!pathFile.exists()) {
            if (FastClasspathScanner.verbose) {
                log.log("Classpath element does not exist: " + pathElement);
            }
            return;
        }
        String pathStr;
        try {
            pathStr = pathFile.getCanonicalPath();
        } catch (final IOException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Exception while getting canonical path for classpath element " + pathElement + ": " + e);
            }
            return;
        }
        if (!classpathElementsSet.add(pathStr)) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring duplicate classpath element: " + pathElement);
            }
            return;
        }

        // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
        // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
        // we recursively call addClasspathElement if needed each time a jar is encountered. 
        if (pathFile.isFile()) {
            if (!isJar(pathStr)) {
                if (FastClasspathScanner.verbose) {
                    log.log("Ignoring non-jar file on classpath: " + pathElement);
                }
                return;
            }
            if (scanSpec.blacklistSystemJars() && isJREJar(pathFile, /* ancestralScanDepth = */2)) {
                // Don't scan system jars if they are blacklisted
                if (FastClasspathScanner.verbose) {
                    log.log("Skipping JRE jar: " + pathElement);
                }
                return;
            }

            // Recursively check for Class-Path entries in the jar manifest file, if present
            final String manifestUrlStr = "jar:" + pathFile.toURI() + "!/META-INF/MANIFEST.MF";
            try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                // Look for Class-Path keys within manifest files
                final Manifest manifest = new Manifest(stream);
                final String manifestClassPath = manifest.getMainAttributes().getValue("Class-Path");
                if (manifestClassPath != null && !manifestClassPath.isEmpty()) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Found Class-Path entry in " + manifestUrlStr + ": " + manifestClassPath);
                    }
                    // Class-Path entries in the manifest file should be resolved relative to
                    // the dir the manifest's jarfile is contained in (i.e. path.getParent()).
                    final File parentPathFile = pathFile.getParentFile();
                    // Class-Path entries in manifest files are a space-delimited list of URIs.
                    for (final String manifestClassPathElement : manifestClassPath.split(" ")) {
                        final File manifestEltPath = urlToFile(parentPathFile, manifestClassPathElement);
                        if (manifestEltPath != null) {
                            addClasspathElement(manifestEltPath.toString());
                        } else {
                            if (FastClasspathScanner.verbose) {
                                log.log("Classpath element " + manifestEltPath
                                        + " not found -- from Class-Path entry " + manifestClassPathElement + " in "
                                        + manifestUrlStr);
                            }
                        }
                    }
                }
            } catch (final IOException e) {
                // Jar does not contain a manifest
            }
        } else if (!pathFile.isDirectory()) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring invalid classpath element: " + pathElement);
            }
            return;
        }

        // Add the classpath element to the ordered list
        if (FastClasspathScanner.verbose) {
            log.log("Found classpath element: " + pathElement);
        }
        // Add the File object to classpathElements
        classpathElements.add(pathFile);
    }

    /** Add classpath elements, separated by the system path separator character. */
    public void addClasspathElements(final String pathStr) {
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement);
            }
        }
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    private boolean isJREJar(final File file, final int ancestralScanDepth) {
        if (ancestralScanDepth == 0) {
            return false;
        } else {
            File parent;
            try {
                parent = file.getParentFile().getCanonicalFile();
                if (parent == null) {
                    return false;
                }
            } catch (final IOException e1) {
                return false;
            }
            final String parentPathStr = parent.getPath();
            if (knownJREPaths.contains(parentPathStr)) {
                return true;
            }
            File rt = new File(parent, "rt.jar");
            if (!rt.exists()) {
                rt = new File(new File(parent, "lib"), "rt.jar");
                if (!rt.exists()) {
                    rt = new File(new File(new File(parent, "jre"), "lib.jar"), "rt.jar");
                }
            }
            if (rt.exists()) {
                // Found rt.jar; check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final String manifestUrlStr = "jar:" + rt.toURI() + "!/META-INF/MANIFEST.MF";
                try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                    // Look for Class-Path keys within manifest files
                    final Manifest manifest = new Manifest(stream);
                    if ("Java Runtime Environment".equals( //
                            manifest.getMainAttributes().getValue("Implementation-Title"))
                            || "Java Platform API Specification".equals( //
                                    manifest.getMainAttributes().getValue("Specification-Title"))) {
                        // Found the JRE's rt.jar
                        knownJREPaths.add(parentPathStr);
                        return true;
                    }
                } catch (final IOException e) {
                    // Jar does not contain a manifest
                }
            }
            return isJREJar(parent, ancestralScanDepth - 1);
        }
    }

    /** Used for resolving the classes in the call stack. Requires RuntimePermission("createSecurityManager"). */
    private static CallerResolver CALLER_RESOLVER;

    static {
        try {
            // This can fail if the current SecurityManager does not allow
            // RuntimePermission ("createSecurityManager"):
            CALLER_RESOLVER = new CallerResolver();
        } catch (final SecurityException e) {
            // Ignore
        }
    }

    // Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
    // https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
    // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }

    }

    /** Add all parents of a ClassLoader in top-down order, the same as in the JRE. */
    private static void addAllParentClassloaders(final ClassLoader classLoader,
            final AdditionOrderedSet<ClassLoader> classLoadersSetOut) {
        final ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
        for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
            callerClassLoaders.add(cl);
        }
        // OpenJDK calls classloaders in a top-down order
        for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
            classLoadersSetOut.add(callerClassLoaders.get(i));
        }
    }

    /** Add all parent ClassLoaders of a class in top-down order, the same as in the JRE. */
    private static void addAllParentClassloaders(final Class<?> klass,
            final AdditionOrderedSet<ClassLoader> classLoadersSetOut) {
        addAllParentClassloaders(klass.getClassLoader(), classLoadersSetOut);
    }

    /** ClassLoaderHandler that is able to extract the URLs from a URLClassLoader. */
    private static class URLClassLoaderHandler implements ClassLoaderHandler {
        @Override
        public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) {
            if (classloader instanceof URLClassLoader) {
                final URL[] urls = ((URLClassLoader) classloader).getURLs();
                if (urls != null) {
                    for (final URL url : urls) {
                        if (url != null) {
                            classpathFinder.addClasspathElement(url.toString());
                        }
                    }
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Add an extra ClassLoaderHandler. Needed if the ServiceLoader framework is not able to find the
     * ClassLoaderHandler for your specific ClassLoader, or if you want to manually register your own
     * ClassLoaderHandler rather than using the ServiceLoader framework.
     */
    public void registerClassLoaderHandler(final ClassLoaderHandler extraClassLoaderHandler) {
        extraClassLoaderHandlers.add(extraClassLoaderHandler);
    }

    /** Override the system classpath with a custom classpath to search. */
    public void overrideClasspath(final String classpath) {
        clearClasspath();
        addClasspathElements(classpath);
        initialized = true;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public List<File> getUniqueClasspathElements() {
        // Parse the system classpath if it hasn't been parsed yet
        if (!initialized) {
            clearClasspath();

            // Look for all unique classloaders.
            // Need both a set and a list so we can keep them unique, but in an order that (hopefully) reflects
            // the order in which the JDK calls classloaders.
            //
            // See:
            // https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html
            //
            // N.B. probably need to look more closely at the exact ordering followed here, see:
            // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2
            //
            final AdditionOrderedSet<ClassLoader> classLoadersSet = new AdditionOrderedSet<>();
            addAllParentClassloaders(ClassLoader.getSystemClassLoader(), classLoadersSet);
            // Look for classloaders on the call stack
            if (CALLER_RESOLVER != null) {
                final Class<?>[] callStack = CALLER_RESOLVER.getClassContext();
                for (final Class<?> callStackClass : callStack) {
                    addAllParentClassloaders(callStackClass, classLoadersSet);
                }
            } else {
                if (FastClasspathScanner.verbose) {
                    log.log(ClasspathFinder.class.getSimpleName() + " could not create "
                            + CallerResolver.class.getSimpleName() + ", current SecurityManager does not grant "
                            + "RuntimePermission(\"createSecurityManager\")");
                }
            }
            addAllParentClassloaders(Thread.currentThread().getContextClassLoader(), classLoadersSet);
            addAllParentClassloaders(ClasspathFinder.class, classLoadersSet);
            final List<ClassLoader> classLoaders = classLoadersSet.getList();
            classLoaders.remove(null);

            // Always include a ClassLoaderHandler for URLClassLoader subclasses as a default, so that we can handle
            // URLClassLoaders (the most common form of ClassLoader) even if ServiceLoader can't find other
            // ClassLoaderHandlers (this can happen if FastClasspathScanner's package is renamed using Maven Shade).
            final Set<ClassLoaderHandler> classLoaderHandlers = new HashSet<>();
            classLoaderHandlers.add(new URLClassLoaderHandler());

            // Find all ClassLoaderHandlers registered using ServiceLoader, given known ClassLoaders. 
            // FastClasspathScanner ships with several of these, registered in:
            // src/main/resources/META-INF/services
            for (final ClassLoaderHandler handler : ServiceLoader.load(ClassLoaderHandler.class, null)) {
                classLoaderHandlers.add(handler);
            }
            for (final ClassLoader classLoader : classLoaders) {
                // Use ServiceLoader to find registered ClassLoaderHandlers, see:
                // https://docs.oracle.com/javase/6/docs/api/java/util/ServiceLoader.html
                final ServiceLoader<ClassLoaderHandler> classLoaderHandlerLoader = ServiceLoader
                        .load(ClassLoaderHandler.class, classLoader);
                // Iterate through registered ClassLoaderHandlers
                for (final ClassLoaderHandler handler : classLoaderHandlerLoader) {
                    classLoaderHandlers.add(handler);
                }
            }
            // Add manually-added ClassLoaderHandlers
            classLoaderHandlers.addAll(extraClassLoaderHandlers);
            // Only keep one instance of each ClassLoaderHandler, in case multiple instances are loaded
            // (due to multiple ClassLoaders covering the same classpath entries)
            final Set<String> classLoaderHandlerNames = new HashSet<>();
            final List<ClassLoaderHandler> classLoaderHandlersUnique = new ArrayList<>();
            for (final ClassLoaderHandler classLoaderHandler : classLoaderHandlers) {
                if (classLoaderHandlerNames.add(classLoaderHandler.getClass().getName())) {
                    classLoaderHandlersUnique.add(classLoaderHandler);
                }
            }
            if (FastClasspathScanner.verbose && !classLoaderHandlers.isEmpty()) {
                log.log("ClassLoaderHandlers loaded: " + Join.join(", ", classLoaderHandlerNames));
            }

            // Try finding a handler for each of the classloaders discovered above
            for (final ClassLoader classLoader : classLoaders) {
                // Iterate through registered ClassLoaderHandlers
                boolean classloaderFound = false;
                for (final ClassLoaderHandler handler : classLoaderHandlersUnique) {
                    try {
                        if (handler.handle(classLoader, this)) {
                            // Sucessfully handled
                            if (FastClasspathScanner.verbose) {
                                log.log("Classpath elements from ClassLoader " + classLoader.getClass().getName()
                                        + " were extracted by ClassLoaderHandler " + handler.getClass().getName());
                            }
                            classloaderFound = true;
                            break;
                        }
                    } catch (final Exception e) {
                        if (FastClasspathScanner.verbose) {
                            log.log("Exception in " + classLoader.getClass().getName() + ": " + e.toString());
                        }
                    }
                }
                if (!classloaderFound) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Found unknown ClassLoader type, cannot scan classes: "
                                + classLoader.getClass().getName());
                    }
                }
            }

            // Add entries found in java.class.path, in case those entries were missed above due to some
            // non-standard classloader that uses this property
            addClasspathElements(System.getProperty("java.class.path"));

            initialized = true;
        }
        return classpathElements;
    }
}
