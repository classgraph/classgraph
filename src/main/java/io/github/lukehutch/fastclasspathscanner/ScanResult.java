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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.json.JSONDeserializer;
import io.github.lukehutch.fastclasspathscanner.json.JSONSerializer;
import io.github.lukehutch.fastclasspathscanner.utils.ClassLoaderAndModuleFinder;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.JarfileMetadataReader;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** The result of a scan. */
public class ScanResult {
    /** The scan spec. */
    final ScanSpec scanSpec;

    /** The order of unique classpath elements. */
    private final List<ClasspathElement> classpathOrder;

    /** A list of all files that were found in whitelisted packages. */
    private ResourceList allResources;

    /**
     * The default order in which ClassLoaders are called to load classes. Used when a specific class does not have
     * a record of which ClassLoader provided the URL used to locate the class (e.g. if the class is found using
     * java.class.path).
     */
    private final ClassLoader[] envClassLoaderOrder;

    /** The nested jar handler instance. */
    private final NestedJarHandler nestedJarHandler;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync().
     */
    private final Map<File, Long> fileToLastModified;

    /**
     * The class graph builder. May be null, if this ScanResult object is the result of a call to
     * FastClasspathScanner#getUniqueClasspathElementsAsync().
     */
    private final Map<String, ClassInfo> classNameToClassInfo;

    /** The log. */
    private final LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /** The result of a scan. Make sure you call complete() after calling the constructor. */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final ClassLoader[] envClassLoaderOrder, final Map<String, ClassInfo> classNameToClassInfo,
            final Map<File, Long> fileToLastModified, final NestedJarHandler nestedJarHandler, final LogNode log) {
        this.scanSpec = scanSpec;
        this.classpathOrder = classpathOrder;
        for (final ClasspathElement classpathElt : classpathOrder) {
            if (classpathElt.fileMatches != null) {
                if (allResources == null) {
                    allResources = new ResourceList();
                }
                allResources.addAll(classpathElt.fileMatches);
            }
        }
        this.envClassLoaderOrder = envClassLoaderOrder;
        this.fileToLastModified = fileToLastModified;
        this.classNameToClassInfo = classNameToClassInfo;
        this.nestedJarHandler = nestedJarHandler;
        this.log = log;

        // Add backrefs from info objects back to this ScanResult
        if (classNameToClassInfo != null) {
            for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                classInfo.setScanResult(this);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of File objects for unique classpath elements (directories or jarfiles), in classloader
     * resolution order.
     *
     * @return The unique classpath elements.
     */
    public List<File> getClasspathFiles() {
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
            if (modRef != null) {
                if (!modRef.isSystemModule()) {
                    // Add module files when they don't have a "jrt:/" scheme
                    final File moduleLocationFile = modRef.getModuleLocationFile();
                    if (moduleLocationFile != null) {
                        classpathElementOrderFiles.add(moduleLocationFile);
                    }
                }
            } else {
                classpathElementOrderFiles.add(classpathElement.getClasspathElementFile(log));
            }
        }
        return classpathElementOrderFiles;
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in classloader resolution order, as a
     * classpath string, delineated with the standard path separator character.
     *
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     */
    public String getClasspath() {
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns the list of unique classpath element paths as URLs, in classloader resolution order.
     *
     * @return The unique classpath element URLs.
     */
    public List<URL> getClasspathURLs() {
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
            if (modRef != null) {
                // Add module URLs whether or not they have a "jrt:/" scheme
                try {
                    classpathElementOrderURLs.add(modRef.getModuleLocation().toURL());
                } catch (final MalformedURLException e) {
                    // Skip malformed URLs (shouldn't happen)
                }
            } else {
                try {
                    classpathElementOrderURLs.add(classpathElement.getClasspathElementFile(log).toURI().toURL());
                } catch (final MalformedURLException e) {
                    // Shouldn't happen
                }
            }
        }
        return classpathElementOrderURLs;
    }

    /** Returns {@ModuleRef} references for all the visible modules. */
    public List<ModuleRef> getModules() {
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef moduleRef = classpathElement.getClasspathElementModuleRef();
            if (moduleRef != null) {
                moduleRefs.add(moduleRef);
            }
        }
        return moduleRefs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get a list of all resources (including classfiles and non-classfiles) found in whitelisted packages. */
    public ResourceList getAllResources() {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            return allResources;
        }
    }

    /**
     * Get a list of all resources found in whitelisted packages that have the given path, relative to the package
     * root of the classpath element. May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String path = resourcePath;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                if (classpathResource.getPathRelativeToPackageRoot().equals(path)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get a list of all resources found in whitelisted packages that have a path, relative to the package root of
     * the classpath element, that starts with the requested path.
     */
    public ResourceList getResourcesWithPathPrefix(final String resourcePathPrefix) {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String pathPrefix = resourcePathPrefix;
            while (pathPrefix.startsWith("/")) {
                pathPrefix = pathPrefix.substring(1);
            }
            if (!pathPrefix.endsWith("/") && !pathPrefix.isEmpty()) {
                pathPrefix = pathPrefix + "/";
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                if (classpathResource.getPathRelativeToPackageRoot().startsWith(pathPrefix)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /** Get a list of all resources found in whitelisted packages that have the requested leafname. */
    public ResourceList getResourcesWithLeafName(final String leafName) {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPathRelativeToPackageRoot();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                if (relativePath.substring(lastSlashIdx + 1).equals(leafName)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get a list of all resources found in whitelisted packages that have the requested extension (e.g. "xml" to
     * match all resources ending in ".xml").
     */
    public ResourceList getResourcesWithExtension(final String extension) {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String bareExtension = extension;
            while (bareExtension.startsWith(".")) {
                bareExtension = bareExtension.substring(1);
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPathRelativeToPackageRoot();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                final int lastDotIdx = relativePath.lastIndexOf('.');
                if (lastDotIdx > lastSlashIdx) {
                    if (relativePath.substring(lastDotIdx + 1).equalsIgnoreCase(bareExtension)) {
                        filteredResources.add(classpathResource);
                    }
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get a list of all resources found in whitelisted packages that have a path, relative to the classpath package
     * root, matching the requested pattern.
     */
    public ResourceList getResourcesMatchingPattern(final Pattern pattern) {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPathRelativeToPackageRoot();
                if (pattern.matcher(relativePath).matches()) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine whether the classpath contents have been modified since the last scan. Checks the timestamps of
     * files and jarfiles encountered during the previous scan to see if they have changed. Does not perform a full
     * scan, so cannot detect the addition of directories that newly match whitelist criteria -- you need to perform
     * a full scan to detect those changes.
     *
     * @return true if the classpath contents have been modified since the last scan.
     */
    public boolean classpathContentsModifiedSinceScan() {
        if (fileToLastModified == null) {
            return true;
        } else {
            for (final Entry<File, Long> ent : fileToLastModified.entrySet()) {
                if (ent.getKey().lastModified() != ent.getValue()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Find the maximum last-modified timestamp of any whitelisted file/directory/jarfile encountered during the
     * scan. Checks the current timestamps, so this should increase between calls if something changes in
     * whitelisted paths. Assumes both file and system timestamps were generated from clocks whose time was
     * accurate. Ignores timestamps greater than the system time.
     * 
     * <p>
     * This method cannot in general tell if classpath has changed (or modules have been added or removed) if it is
     * run twice during the same runtime session.
     *
     * @return the maximum last-modified time for whitelisted files/directories/jars encountered during the scan.
     */
    public long classpathContentsLastModifiedTime() {
        long maxLastModifiedTime = 0L;
        if (fileToLastModified != null) {
            final long currTime = System.currentTimeMillis();
            for (final long timestamp : fileToLastModified.values()) {
                if (timestamp > maxLastModifiedTime && timestamp < currTime) {
                    maxLastModifiedTime = timestamp;
                }
            }
        }
        return maxLastModifiedTime;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /**
     * Get the ClassInfo object for the named class, or null if no class of the requested name was found in a
     * whitelisted/non-blacklisted package during scanning.
     * 
     * @return The ClassInfo object for the named class, or null if the class was not found.
     */
    public ClassInfo getClassInfo(final String className) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return classNameToClassInfo.get(className);
    }

    /**
     * Get all classes, interfaces and annotations found during the scan.
     *
     * @return A list of all whitelisted classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllClasses() {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return A list of all whitelisted standard classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllStandardClasses() {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all subclasses of the named superclass.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of subclasses of the named superclass, or the empty list if none.
     */
    public ClassInfoList getSubclasses(final String superclassName) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        final ClassInfo superclass = classNameToClassInfo.get(superclassName);
        return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getSubclasses();
    }

    /**
     * Get superclasses of the named subclass.
     *
     * @param subclassName
     *            The name of the subclass.
     * @return A list of superclasses of the named subclass, or the empty list if none.
     */
    public ClassInfoList getSuperclasses(final String subclassName) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        final ClassInfo subclass = classNameToClassInfo.get(subclassName);
        return subclass == null ? ClassInfoList.EMPTY_LIST : subclass.getSuperclasses();
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotationName
     *            the name of the method annotation.
     * @return A list of classes with a method that has an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final String methodAnnotationName) {
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo(), #enableMethodInfo(), "
                            + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(methodAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodAnnotation();
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotationName
     *            the name of the field annotation.
     * @return A list of classes that have a field with an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final String fieldAnnotationName) {
        if (!scanSpec.enableClassInfo || !scanSpec.enableFieldInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo(), #enableFieldInfo(), "
                            + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(fieldAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithFieldAnnotation();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get all interface classes found during the scan (not including annotations, which are also technically
     * interfaces). See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfaces() {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all interfaces implemented by the named class or by one of its superclasses, if this is a standard class,
     * or the superinterfaces extended by this interface, if this is an interface.
     *
     * @param className
     *            The class name.
     * @return A list of interfaces implemented by the named class (or superinterfaces extended by the named
     *         interface), or the empty list if none.
     */
    public ClassInfoList getInterfaces(final String className) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getInterfaces();
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the named interface (or one of its
     * subinterfaces).
     *
     * @param interfaceName
     *            The interface name.
     * @return A list of all classes that implement the named interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing(final String interfaceName) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesImplementing();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get all annotation classes found during the scan. See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllAnnotations() {
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo() and #enableAnnotationInfo() "
                            + "before #scan()");
        }
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo() and #enableAnnotationInfo() "
                            + "before #scan()");
        }
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get non-annotation classes with the named class annotation or meta-annotation.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the named class annotation during the scan,
     *         or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation(final String annotationName) {
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo() and #enableAnnotationInfo() "
                            + "before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithAnnotation();
    }

    /**
     * Get annotations on the named class. This only returns the annotating classes; to read annotation parameters,
     * call {@link #getClassInfo(String)} to get the {@link ClassInfo} object for the named class, then if the
     * {@link ClassInfo} object is non-null, call {@link ClassInfo#getAnnotationInfo()} to get detailed annotation
     * info.
     *
     * @param className
     *            The name of the class.
     * @return A list of all annotation classes that were found with the named class annotation during the scan, or
     *         the empty list if none.
     */
    public ClassInfoList getAnnotationsOnClass(final String className) {
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableClassInfo() and #enableAnnotationInfo() "
                            + "before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAnnotations();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     * 
     * <p>
     * Note that if you call this with showFields or showMethods set to false, but with method and/or field info
     * enabled during scanning, then arrows will still be added between classes even if the field or method that
     * created that dependency is not shown.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param showFields
     *            If true, show fields within class nodes in the graph. To show field info,
     *            {@link FastClasspathScanner#enableFieldInfo()} should be called before scanning. You may also want
     *            to call {@link FastClasspathScanner#ignoreFieldVisibility()} before scanning, to show non-public
     *            fields.
     * @param showMethods
     *            If true, show methods within class nodes in the graph. To show method info,
     *            {@link FastClasspathScanner#enableMethodInfo()} should be called before scanning. You may also
     *            want to call {@link FastClasspathScanner#ignoreMethodVisibility()} before scanning, to show
     *            non-public methods.
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY, final boolean showFields,
            final boolean showMethods) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return GraphvizDotfileGenerator.generateClassGraphDotFile(this, sizeX, sizeY, showFields, showMethods,
                scanSpec);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. Methods
     * and fields are shown, if method and field info have been enabled respectively, via
     * {@link FastClasspathScanner#enableMethodInfo()} and {@link FastClasspathScanner#enableFieldInfo()}. Only
     * public methods/fields are shown, unless {@link FastClasspathScanner#ignoreMethodVisibility()} and/or
     * {@link FastClasspathScanner#ignoreFieldVisibility()} has been called. The sizeX and sizeY parameters are the
     * image output size to use (in inches) when GraphViz is asked to render the .dot file.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return generateClassGraphDotFile(sizeX, sizeY, /* showFields = */ true, /* showMethods = */ true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. Methods
     * and fields are shown, if method and field info have been enabled respectively, via
     * {@link FastClasspathScanner#enableMethodInfo()} and {@link FastClasspathScanner#enableFieldInfo()}. Only
     * public methods/fields are shown, unless {@link FastClasspathScanner#ignoreMethodVisibility()} and/or
     * {@link FastClasspathScanner#ignoreFieldVisibility()} has been called. The size defaults to 10.5 x 8 inches.
     *
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile() {
        return generateClassGraphDotFile(/* sizeX = */ 10.5f, /* sizeY = */ 8f, /* showFields = */ true,
                /* showMethods = */ true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, and save
     * it to the specified file. Methods and fields are shown, if method and field info have been enabled
     * respectively, via {@link FastClasspathScanner#enableMethodInfo()} and
     * {@link FastClasspathScanner#enableFieldInfo()}. Only public methods/fields are shown, unless
     * {@link FastClasspathScanner#ignoreMethodVisibility()} and/or
     * {@link FastClasspathScanner#ignoreFieldVisibility()} has been called. The size defaults to 10.5 x 8 inches.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @throws IOException
     *             if the file could not be saved.
     */
    public void generateClassGraphDotFile(final File file) throws IOException {
        try (final PrintWriter writer = new PrintWriter(file)) {
            writer.print(generateClassGraphDotFile());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className, initializeLoadedClasses, classLoader), for all known
     * ClassLoaders, until one is able to load the class, or until there are no more ClassLoaders to try.
     *
     * @throw IllegalArgumentException if LinkageError (including ExceptionInInitializerError) is thrown, or if no
     *        ClassLoader is able to load the class.
     * @return a reference to the loaded class, or null if the class could not be found.
     */
    private Class<?> loadClass(final String className, final ClassLoader classLoader, final LogNode log)
            throws IllegalArgumentException {
        try {
            return Class.forName(className, scanSpec.initializeLoadedClasses, classLoader);
        } catch (final ClassNotFoundException e) {
            return null;
        } catch (final Throwable e) {
            throw new IllegalArgumentException("Exception while loading class " + className, e);
        }
    }

    /**
     * Call the classloader using Class.forName(className, initializeLoadedClasses, classLoader), for all known
     * ClassLoaders, until one is able to load the class, or until there are no more ClassLoaders to try.
     *
     * @throw IllegalArgumentException if LinkageError (including ExceptionInInitializerError) is thrown, or if
     *        returnNullIfClassNotFound is false and no ClassLoader is able to load the class.
     * @return a reference to the loaded class, or null if returnNullIfClassNotFound was true and the class could
     *         not be loaded.
     */
    Class<?> loadClass(final String className, final boolean returnNullIfClassNotFound, final LogNode log)
            throws IllegalArgumentException {
        if (scanSpec.overrideClasspath != null) {
            // Unfortunately many surprises can result when overriding classpath (e.g. the wrong class definition
            // could be loaded, if a class is defined more than once in the classpath). Even when overriding the
            // ClassLoaders, a class may not be able to be cast to its superclass, if the class and its superclass
            // are loaded into different classloaders, possibly due to accidental loading and caching in the
            // non-custom classloader). Basically if you're overriding the classpath and/or defining custom
            // classloaders, bad things will probably happen at some point!
            if (log != null) {
                log.log("When loading classes from a custom classpath, defined using .overrideClasspath(), "
                        + "the correct classloader for the requested class " + className
                        + " cannot reliably be determined. Will try loading the class using the default context "
                        + "classLoader (which may or may not even be able to find the class). "
                        + "Preferably always use .overrideClassLoaders() instead.");
            }
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("Cannot load class -- class names cannot be null or empty");
        }

        // Try loading class via each classloader in turn
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        final ClassLoader[] classLoadersForClass = classInfo != null ? classInfo.classLoaders : envClassLoaderOrder;
        if (classLoadersForClass != null) {
            for (final ClassLoader classLoader : classLoadersForClass) {
                final Class<?> classRef = loadClass(className, classLoader, log);
                if (classRef != null) {
                    return classRef;
                }
            }
        }
        // Try with null (bootstrap) ClassLoader
        final Class<?> classRef = loadClass(className, /* classLoader = */ null, log);
        if (classRef != null) {
            return classRef;
        }

        // If this class came from a jarfile with a package root (e.g. a Spring-Boot jar, with packages rooted
        // at BOOT-INF/classes), then the problem is probably that the jarfile was on the classpath, but the
        // scanner is not running inside the jar itself, so the necessary ClassLoader for the jar is not
        // available. Unzip the jar starting from the package root, and create a URLClassLoader to load classes
        // from the unzipped jar. (This is only done once per jar and package root, using the singleton pattern.)
        if (classInfo != null && nestedJarHandler != null) {
            try {
                ClassLoader customClassLoader = null;
                if (classInfo.classpathElementFile.isDirectory()) {
                    // Should not happen, but we should handle this anyway -- create a URLClassLoader for the dir
                    customClassLoader = new URLClassLoader(
                            new URL[] { classInfo.classpathElementFile.toURI().toURL() });
                } else {
                    // Get the outermost jar containing this jarfile, so that if a lib jar was extracted during
                    // scanning, we obtain the classloader from the outer jar. This is needed so that package roots
                    // and lib jars are loaded from the same classloader.
                    final File outermostJar = nestedJarHandler.getOutermostJar(classInfo.classpathElementFile);

                    // Get jarfile metadata for classpath element jarfile
                    final JarfileMetadataReader jarfileMetadataReader = //
                            nestedJarHandler.getJarfileMetadataReader(outermostJar, "", log);

                    // Create a custom ClassLoader for the jarfile. This might be time consuming, as it could
                    // trigger the extraction of all classes (for a classpath root other than ""), and/or any
                    // lib jars (e.g. in BOOT-INF/lib).
                    customClassLoader = jarfileMetadataReader.getCustomClassLoader(nestedJarHandler, log);
                }
                if (customClassLoader != null) {
                    final Class<?> classRefFromCustomClassLoader = loadClass(className, customClassLoader, log);
                    if (classRefFromCustomClassLoader != null) {
                        return classRefFromCustomClassLoader;
                    }
                } else {
                    if (log != null) {
                        log.log("Unable to create custom classLoader to load class " + className);
                    }
                }
            } catch (final Throwable e) {
                if (log != null) {
                    log.log("Exception while trying to load class " + className + " : " + e);
                }
            }
        }

        // Could not load class
        if (!returnNullIfClassNotFound) {
            throw new IllegalArgumentException("No classloader was able to load class " + className);
        } else {
            if (log != null) {
                log.log("No classloader was able to load class " + className);
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            the class to load.
     * @param ignoreExceptions
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             or initializing the class. (Note that class initialization on load is disabled by default, you
     *             can enable it with {@code FastClasspathScanner#initializeLoadedClasses(true)} .) Otherwise
     *             exceptions are suppressed, and null is returned if any of these problems occurs.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    public Class<?> loadClass(final String className, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        try {
            return loadClass(className, /* returnNullIfClassNotFound = */ ignoreExceptions, log);
        } catch (final Throwable e) {
            if (ignoreExceptions) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className, e);
            }
        } finally {
            // Manually flush log, since this method is called after scanning is complete
            if (log != null) {
                log.flush();
            }
        }
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            the class to load.
     * @param classType
     *            The class type to cast the result to.
     * @param ignoreExceptions
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             the class, initializing the class, or casting it to the requested type. (Note that class
     *             initialization on load is disabled by default, you can enable it with
     *             {@code FastClasspathScanner#initializeLoadedClasses(true)} .) Otherwise exceptions are
     *             suppressed, and null is returned if any of these problems occurs.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    <T> Class<T> loadClass(final String className, final Class<T> classType, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        try {
            if (classType == null) {
                if (ignoreExceptions) {
                    return null;
                } else {
                    throw new IllegalArgumentException("classType parameter cannot be null");
                }
            }
            final Class<?> loadedClass = loadClass(className, /* returnNullIfClassNotFound = */ ignoreExceptions,
                    log);
            if (loadedClass != null && !classType.isAssignableFrom(loadedClass)) {
                if (ignoreExceptions) {
                    return null;
                } else {
                    throw new IllegalArgumentException(
                            "Loaded class " + loadedClass.getName() + " cannot be cast to " + classType.getName());
                }
            }
            @SuppressWarnings("unchecked")
            final Class<T> castClass = (Class<T>) loadedClass;
            return castClass;
        } catch (final Throwable e) {
            if (ignoreExceptions) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className, e);
            }
        } finally {
            // Manually flush log, since this method is called after scanning is complete
            if (log != null) {
                log.flush();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The current serialization format. */
    private static final String CURRENT_SERIALIZATION_FORMAT = "4";

    /** A class to hold a serialized ScanResult along with the ScanSpec that was used to scan. */
    private static class SerializationFormat {
        public String serializationFormat;
        public ScanSpec scanSpec;
        public List<ClassInfo> allClassInfo;

        @SuppressWarnings("unused")
        public SerializationFormat() {
        }

        public SerializationFormat(final String serializationFormat, final ScanSpec scanSpec,
                final Map<String, ClassInfo> classNameToClassInfo) {
            this.serializationFormat = serializationFormat;
            this.scanSpec = scanSpec;
            this.allClassInfo = new ArrayList<>(classNameToClassInfo.values());
        }
    }

    /** Deserialize a ScanResult from previously-saved JSON. */
    public static ScanResult fromJSON(final String json) {
        final Matcher matcher = Pattern.compile("\\{[\\n\\r ]*\"serializationFormat\"[ ]?:[ ]?\"([^\"]+)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("JSON is not in correct format");
        }
        if (!CURRENT_SERIALIZATION_FORMAT.equals(matcher.group(1))) {
            throw new IllegalArgumentException(
                    "JSON was serialized in a different format from the format used by the current version of "
                            + "FastClasspathScanner -- please serialize and deserialize your ScanResult using "
                            + "the same version of FastClasspathScanner");
        }

        final SerializationFormat deserialized = JSONDeserializer.deserializeObject(SerializationFormat.class,
                json);
        if (!deserialized.serializationFormat.equals(CURRENT_SERIALIZATION_FORMAT)) {
            // Probably the deserialization failed before now anyway, if fields have changed, etc.
            throw new IllegalArgumentException("JSON was serialized by newer version of FastClasspathScanner");
        }
        final ClassLoader[] envClassLoaderOrder = new ClassLoaderAndModuleFinder(deserialized.scanSpec,
                /* log = */ null).getClassLoaders();
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        for (final ClassInfo ci : deserialized.allClassInfo) {
            classNameToClassInfo.put(ci.getClassName(), ci);
        }
        final ScanResult scanResult = new ScanResult(deserialized.scanSpec,
                /* classpathOrder = */ Collections.<ClasspathElement> emptyList(), envClassLoaderOrder,
                classNameToClassInfo, /* fileToLastModified = */ null, /* nestedJarHandler = */ null,
                /* log = */ null);
        return scanResult;
    }

    /**
     * Serialize a ScanResult to JSON.
     * 
     * @param indentWidth
     *            If greater than 0, JSON will be formatted (indented), otherwise it will be minified (un-indented).
     */
    public String toJSON(final int indentWidth) {
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableClassInfo() (or adding class or package whitelist/blacklist "
                    + "criteria) before starting the scan");
        }
        return JSONSerializer.serializeObject(
                new SerializationFormat(CURRENT_SERIALIZATION_FORMAT, scanSpec, classNameToClassInfo), indentWidth,
                false);
    }

    /** Serialize a ScanResult to minified (un-indented) JSON. */
    public String toJSON() {
        return toJSON(0);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Free any temporary files created by extracting jars from within jars. By default, temporary files are removed
     * at the end of a scan, after MatchProcessors have completed, so this typically does not need to be called. The
     * case where it might need to be called is if the list of classpath elements has been fetched, and the
     * classpath contained jars within jars. Without calling this method, the temporary files created by extracting
     * the inner jars will not be removed until the temporary file system cleans them up (typically at reboot).
     * 
     * @param log
     *            The log.
     */
    public void freeTempFiles(final LogNode log) {
        if (allResources != null) {
            for (final Resource classpathResource : allResources) {
                classpathResource.close();
            }
        }
        if (nestedJarHandler != null) {
            nestedJarHandler.close(log);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        // NestedJarHandler also adds a runtime shutdown hook, since finalizers are not reliable
        freeTempFiles(null);
    }
}
