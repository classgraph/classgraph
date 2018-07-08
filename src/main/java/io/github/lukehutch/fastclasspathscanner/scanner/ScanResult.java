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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.json.JSONDeserializer;
import io.github.lukehutch.fastclasspathscanner.json.JSONSerializer;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** The result of a scan. */
public class ScanResult {
    /** The scan spec. */
    transient ScanSpec scanSpec;

    /** The order of unique classpath elements. */
    transient List<ClasspathElement> classpathOrder;

    /**
     * The default order in which ClassLoaders are called to load classes. Used when a specific class does not have
     * a record of which ClassLoader provided the URL used to locate the class (e.g. if the class is found using
     * java.class.path).
     */
    private transient ClassLoader[] envClassLoaderOrder;

    /** The nested jar handler instance. */
    private transient NestedJarHandler nestedJarHandler;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync().
     */
    private transient Map<File, Long> fileToLastModified;

    /**
     * The class graph builder. May be null, if this ScanResult object is the result of a call to
     * FastClasspathScanner#getUniqueClasspathElementsAsync().
     */
    ClassGraphBuilder classGraphBuilder;

    /** Exceptions thrown while loading classes or while calling MatchProcessors on loaded classes. */
    private transient List<Throwable> matchProcessorExceptions = new ArrayList<>();

    /** The interruption checker. */
    transient InterruptionChecker interruptionChecker;

    /** The log. */
    transient LogNode log;

    abstract static class InfoObject {
        /** Set ScanResult references in info objects after scan has completed. */
        abstract void setScanResult(ScanResult scanResult);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The result of a scan. Make sure you call complete() after calling the constructor. */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final ClassLoader[] envClassLoaderOrder, final ClassGraphBuilder classGraphBuilder,
            final Map<File, Long> fileToLastModified, final NestedJarHandler nestedJarHandler,
            final InterruptionChecker interruptionChecker, final LogNode log) {
        this.scanSpec = scanSpec;
        this.classpathOrder = classpathOrder;
        this.envClassLoaderOrder = envClassLoaderOrder;
        this.fileToLastModified = fileToLastModified;
        this.classGraphBuilder = classGraphBuilder;
        this.nestedJarHandler = nestedJarHandler;
        this.interruptionChecker = interruptionChecker;
        this.log = log;

        // classGraphBuilder is null when only getting classpath elements
        if (classGraphBuilder != null) {
            // Add some post-scan backrefs from info objects to this ScanResult
            if (classGraphBuilder.getClassNameToClassInfo() != null) {
                for (final ClassInfo ci : classGraphBuilder.getClassNameToClassInfo().values()) {
                    ci.setScanResult(this);
                }
            }
        }
    }

    /**
     * Find the classloader(s) for the named class. Typically there will only be one ClassLoader returned. However,
     * if more than one is returned, they should be called in turn until one is able to load the class.
     * 
     * @param className
     *            The class name.
     * @return The classloader(s) for the named class.
     */
    public ClassLoader[] getClassLoadersForClass(final String className) {
        final Map<String, ClassLoader[]> classNameToClassLoaders = classGraphBuilder.getClassNameToClassLoaders();
        if (classNameToClassLoaders != null) {
            final ClassLoader[] classLoaders = classNameToClassLoaders.get(className);
            if (classLoaders != null) {
                return classLoaders;
            }
        }
        // Default to default classloader order if classpath element didn't have specified classloader(s)
        return envClassLoaderOrder;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Called if classloading fails, or if a MatchProcessor throws an exception or error. */
    void addMatchProcessorException(final Throwable e) {
        matchProcessorExceptions.add(e);
    }

    /**
     * Return the exceptions and errors thrown during classloading and/or while calling MatchProcessors on loaded
     * classes.
     *
     * @return A list of Throwables thrown while MatchProcessors were running.
     */
    // TODO: currently, if a match processor exception is thrown, FastClasspathScanner throws an exception 
    // once scanning is finished. This means that ScanResult is never actually returned. Need to add a
    // configuration option for suppressing warnings, so that the exceptions list can be fetched from
    // ScanResult on exit.
    public List<Throwable> getMatchProcessorExceptions() {
        return matchProcessorExceptions;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of File objects for unique classpath elements (directories or jarfiles), in classloader
     * resolution order.
     *
     * @return The unique classpath elements.
     */
    public List<File> getUniqueClasspathElements() {
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
            if (modRef != null) {
                if (!modRef.isSystemModule()) {
                    // Add module files when they don't have a "jrt:/" scheme
                    classpathElementOrderFiles.add(modRef.getModuleLocationFile());
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
    public String getUniqueClasspathElementsAsPathStr() {
        return JarUtils.pathElementsToPathStr(getUniqueClasspathElements());
    }

    /**
     * Returns the list of unique classpath element paths as URLs, in classloader resolution order.
     *
     * @return The unique classpath element URLs.
     */
    public List<URL> getUniqueClasspathElementURLs() {
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
    // ClassInfo (may be filtered as a Java 8 stream)

    /**
     * Get a map from class name to ClassInfo object for all whitelisted classes found during the scan. You can get
     * the info for a specific class directly from this map, or the values() of this map may be filtered using Java
     * 8 stream processing, see here:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#mechanism-3
     *
     * @return A map from class name to ClassInfo object for the class.
     */
    public Map<String, ClassInfo> getClassNameToClassInfo() {
        return classGraphBuilder.getClassNameToClassInfo();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /**
     * Get the names of all classes, interfaces and annotations found during the scan.
     *
     * @return The sorted list of the names of all whitelisted classes found during the scan, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAllClasses() {
        return classGraphBuilder.getNamesOfAllClasses();
    }

    /**
     * Get the names of all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return The sorted list of the names of all encountered standard classes, or the empty list if none.
     */
    public List<String> getNamesOfAllStandardClasses() {
        return classGraphBuilder.getNamesOfAllStandardClasses();
    }

    /**
     * Get the names of all subclasses of the named class.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName) {
        return classGraphBuilder.getNamesOfSubclassesOf(superclassName);
    }

    /**
     * Get the names of classes on the classpath that extend the specified superclass.
     *
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final Class<?> superclass) {
        return classGraphBuilder.getNamesOfSubclassesOf(scanSpec.getStandardClassName(superclass));
    }

    /**
     * Get the names of classes on the classpath that are superclasses of the named subclass.
     *
     * @param subclassName
     *            The name of the subclass.
     * @return The sorted list of the names of superclasses of the named subclass, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName) {
        return classGraphBuilder.getNamesOfSuperclassesOf(subclassName);
    }

    /**
     * Get the names of classes on the classpath that are superclasses of the specified subclass.
     *
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return The sorted list of the names of superclasses of the subclass, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final Class<?> subclass) {
        return getNamesOfSuperclassesOf(scanSpec.getStandardClassName(subclass));
    }

    /**
     * Get the names of classes that have a method with an annotation of the named type.
     *
     * @param annotationName
     *            the name of the method annotation.
     * @return The sorted list of the names of classes with a method that has an annotation of the named type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithMethodAnnotation(final String annotationName) {
        if (!scanSpec.enableMethodAnnotationIndexing) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableMethodAnnotationIndexing() before calling scan() -- "
                            + "method annotation indexing is disabled by default for efficiency");
        }
        return classGraphBuilder.getNamesOfClassesWithMethodAnnotation(annotationName);
    }

    /**
     * Get the names of classes that have a method with an annotation of the given type.
     *
     * @param annotation
     *            the method annotation.
     * @return The sorted list of the names of classes with a method that has an annotation of the given type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithMethodAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithMethodAnnotation(scanSpec.getAnnotationName(annotation));
    }

    /**
     * Get the names of classes that have a field with an annotation of the named type.
     *
     * @param annotationName
     *            the name of the field annotation.
     * @return The sorted list of the names of classes that have a field with an annotation of the named type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldAnnotation(final String annotationName) {
        if (!scanSpec.enableFieldAnnotationIndexing) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableFieldAnnotationIndexing() before calling scan() -- "
                            + "field annotation indexing is disabled by default for efficiency");
        }
        return classGraphBuilder.getNamesOfClassesWithFieldAnnotation(annotationName);
    }

    /**
     * Get the names of classes that have a field with an annotation of the given type.
     *
     * @param annotation
     *            the field annotation.
     * @return The sorted list of the names of classes that have a field with an annotaton of the given type, or the
     *         empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithFieldAnnotation(scanSpec.getAnnotationName(annotation));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get the names of all interface classes found during the scan.
     *
     * @return The sorted list of the names of all whitelisted interfaces found during the scan, or the empty list
     *         if none.
     */
    public List<String> getNamesOfAllInterfaceClasses() {
        return classGraphBuilder.getNamesOfAllInterfaceClasses();
    }

    /**
     * Get the names of all subinterfaces of the named interface.
     *
     * @param interfaceName
     *            The interface name.
     * @return The sorted list of the names of all subinterfaces of the named interface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return classGraphBuilder.getNamesOfSubinterfacesOf(interfaceName);
    }

    /**
     * Get the names of interfaces on the classpath that extend a given superinterface.
     *
     * @param superInterface
     *            The superinterface.
     * @return The sorted list of the names of subinterfaces of the given superinterface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final Class<?> superInterface) {
        return getNamesOfSubinterfacesOf(scanSpec.getInterfaceName(superInterface));
    }

    /**
     * Get the names of all superinterfaces of the named interface.
     *
     * @param subInterfaceName
     *            The subinterface name.
     * @return The sorted list of the names of superinterfaces of the named subinterface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subInterfaceName) {
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterfaceName);
    }

    /**
     * Get the names of all superinterfaces of the given subinterface.
     *
     * @param subInterface
     *            The subinterface.
     * @return The sorted list of the names of superinterfaces of the given subinterface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final Class<?> subInterface) {
        return getNamesOfSuperinterfacesOf(scanSpec.getInterfaceName(subInterface));
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) the named interface (or one
     * of its subinterfaces).
     *
     * @param interfaceName
     *            The interface name.
     * @return The sorted list of the names of all classes that implement the named interface, or the empty list if
     *         none.
     */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        return classGraphBuilder.getNamesOfClassesImplementing(interfaceName);
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) the given interface (or one
     * of its subinterfaces).
     *
     * @param implementedInterface
     *            The interface.
     * @return The sorted list of the names of all classes that implement the given interface, or the empty list if
     *         none.
     */
    public List<String> getNamesOfClassesImplementing(final Class<?> implementedInterface) {
        return getNamesOfClassesImplementing(scanSpec.getInterfaceName(implementedInterface));
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) all of the named interfaces
     * (or their subinterfaces).
     *
     * @param implementedInterfaceNames
     *            The names of the interfaces.
     * @return The sorted list of the names of all classes that implement all of the named interfaces, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final String... implementedInterfaceNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < implementedInterfaceNames.length; i++) {
            final String implementedInterfaceName = implementedInterfaceNames[i];
            final List<String> namesOfImplementingClasses = getNamesOfClassesImplementing(implementedInterfaceName);
            if (i == 0) {
                classNames.addAll(namesOfImplementingClasses);
            } else {
                classNames.retainAll(namesOfImplementingClasses);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) all of the given interfaces
     * (or their subinterfaces).
     *
     * @param implementedInterfaces
     *            The interfaces.
     * @return The sorted list of the names of all classes that implement all of the given interfaces, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final Class<?>... implementedInterfaces) {
        return getNamesOfClassesImplementingAllOf(scanSpec.getInterfaceNames(implementedInterfaces));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get the names of all annotation classes found during the scan.
     *
     * @return The sorted list of the names of all annotation classes found during the scan, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAllAnnotationClasses() {
        return classGraphBuilder.getNamesOfAllAnnotationClasses();
    }

    /**
     * Get the names of non-annotation classes with the named class annotation or meta-annotation.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @return The sorted list of the names of all non-annotation classes that were found with the named class
     *         annotation during the scan, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotationName);
    }

    /**
     * Get the names of non-annotation classes with the given class annotation or meta-annotation.
     *
     * @param annotation
     *            The class annotation or meta-annotation to match.
     * @return The sorted list of the names of all non-annotation classes that were found with the given class
     *         annotation during the scan, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithAnnotation(scanSpec.getAnnotationName(annotation));
    }

    /**
     * Get the names of classes that have all of the named annotations.
     *
     * @param annotationNames
     *            The class annotation names.
     * @return The sorted list of the names of classes that have all of the named class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < annotationNames.length; i++) {
            final String annotationName = annotationNames[i];
            final List<String> namesOfClassesWithMetaAnnotation = getNamesOfClassesWithAnnotation(annotationName);
            if (i == 0) {
                classNames.addAll(namesOfClassesWithMetaAnnotation);
            } else {
                classNames.retainAll(namesOfClassesWithMetaAnnotation);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Get the names of classes that have all of the given annotations.
     *
     * @param annotations
     *            The class annotations.
     * @return The sorted list of the names of classes that have all of the given class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAllOf(scanSpec.getAnnotationNames(annotations));
    }

    /**
     * Get the names of classes that have any of the named annotations.
     *
     * @param annotationNames
     *            The annotation names.
     * @return The sorted list of the names of classes that have any of the named class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (final String annotationName : annotationNames) {
            classNames.addAll(getNamesOfClassesWithAnnotation(annotationName));
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Get the names of classes that have any of the given annotations.
     *
     * @param annotations
     *            The annotations.
     * @return The sorted list of the names of classes that have any of the given class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAnyOf(scanSpec.getAnnotationNames(annotations));
    }

    /**
     * Get the names of all annotations and meta-annotations on the named class.
     *
     * @param className
     *            The class name.
     * @return The sorted list of the names of annotations and meta-annotations on the named class, or the empty
     *         list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final String className) {
        return classGraphBuilder.getNamesOfAnnotationsOnClass(className);
    }

    /**
     * Get the names of all annotations and meta-annotations on the given class.
     *
     * @param klass
     *            The class.
     * @return The sorted list of the names of annotations and meta-annotations on the given class, or the empty
     *         list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final Class<?> klass) {
        return getNamesOfAnnotationsOnClass(scanSpec.getClassOrInterfaceName(klass));
    }

    /**
     * Return the names of all annotations that have the named meta-annotation.
     *
     * @param metaAnnotationName
     *            The name of the meta-annotation.
     * @return The sorted list of the names of annotations that have the named meta-annotation, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return classGraphBuilder.getNamesOfAnnotationsWithMetaAnnotation(metaAnnotationName);
    }

    /**
     * Return the names of all annotations that have the named meta-annotation.
     *
     * @param metaAnnotation
     *            The meta-annotation.
     * @return The sorted list of the names of annotations that have the given meta-annotation, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final Class<?> metaAnnotation) {
        return getNamesOfAnnotationsWithMetaAnnotation(scanSpec.getAnnotationName(metaAnnotation));
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
     *            {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableFieldInfo()} should be
     *            called before scanning. You may also want to call
     *            {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreFieldVisibility()}
     *            before scanning, to show non-public fields.
     * @param showMethods
     *            If true, show methods within class nodes in the graph. To show method info,
     *            {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableMethodInfo()} should be
     *            called before scanning. You may also want to call
     *            {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreMethodVisibility()}
     *            before scanning, to show non-public methods.
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY, final boolean showFields,
            final boolean showMethods) {
        return classGraphBuilder.generateClassGraphDotFile(sizeX, sizeY, showFields, showMethods);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. Methods
     * and fields are shown, if method and field info have been enabled respectively, via
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableMethodInfo()} and
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableFieldInfo()}. Only public
     * methods/fields are shown, unless
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreMethodVisibility()} and/or
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreFieldVisibility()} has been
     * called. The sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to
     * render the .dot file.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return classGraphBuilder.generateClassGraphDotFile(sizeX, sizeY, /* showFields = */ true,
                /* showMethods = */ true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. Methods
     * and fields are shown, if method and field info have been enabled respectively, via
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableMethodInfo()} and
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#enableFieldInfo()}. Only public
     * methods/fields are shown, unless
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreMethodVisibility()} and/or
     * {@link io.github.lukehutch.fastclasspathscanner.FastClasspathScanner#ignoreFieldVisibility()} has been
     * called. The size defaults to 10.5 x 8 inches.
     *
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile() {
        return classGraphBuilder.generateClassGraphDotFile(/* sizeX = */ 10.5f, /* sizeY = */ 8f,
                /* showFields = */ true, /* showMethods = */ true);
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
        ClassLoader[] classLoadersForClass = getClassLoadersForClass(className);
        if (classLoadersForClass == null) {
            classLoadersForClass = envClassLoaderOrder;
        }
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
        final ClassInfo classInfo = classGraphBuilder.classNameToClassInfo.get(className);
        if (classInfo != null && nestedJarHandler != null) {
            try {
                final ClassLoader customClassLoader = nestedJarHandler.getCustomClassLoaderForPackageRoot(
                        classInfo.classpathElementFile, classInfo.jarfilePackageRoot, log);
                final Class<?> customLoaderClassRef = loadClass(className, customClassLoader, log);
                if (customLoaderClassRef != null) {
                    return customLoaderClassRef;
                }
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Could not create custom URLClassLoader to load class " + className + " from "
                            + classInfo.classpathElementFile + "!/" + classInfo.jarfilePackageRoot, e);
                }
                if (returnNullIfClassNotFound) {
                    return null;
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
     * Produce a list of Class references given a list of class names. If ignoreExceptions is true, and any classes
     * cannot be loaded (due to classloading error, or due to an exception being thrown in the class initialization
     * block), an IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is
     * thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param classNames
     *            The list of names of classes to load.
     * @param ignoreExceptions
     *            If true, exceptions are ignored during classloading, otherwise IllegalArgumentException is thrown
     *            if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             or initializing the classes. (Note that class initialization on load is disabled by default, you
     *             can enable it with {@code FastClasspathScanner#initializeLoadedClasses(true)} .) Otherwise
     *             exceptions are suppressed, and classes are not added to the resulting list if loading them
     *             exhibits any of these problems.
     * @return a list of references to the loaded classes.
     */
    public List<Class<?>> classNamesToClassRefs(final List<String> classNames, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        if (classNames.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> classRefs = new ArrayList<>();
            // Try loading each class
            for (final String className : classNames) {
                final Class<?> classRef = classNameToClassRef(className, ignoreExceptions);
                if (classRef != null) {
                    classRefs.add(classRef);
                }
            }
            return classRefs.isEmpty() ? Collections.<Class<?>> emptyList() : classRefs;
        }
    }

    /**
     * Produce a list of Class references, given a list of names of classes extending a common superclass or
     * implementing a common interface. If ignoreExceptions is true, and any classes cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param classNames
     *            The list of names of classes to load.
     * @param commonClassType
     *            The common superclass of, or interface implemented by, the named classes.
     * @param ignoreExceptions
     *            If true, exceptions are ignored during classloading, otherwise IllegalArgumentException is thrown
     *            if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             the classes, initializing the classes, or casting them to the requested type. (Note that class
     *             initialization on load is disabled by default, you can enable it with
     *             {@code FastClasspathScanner#initializeLoadedClasses(true)} .) Otherwise exceptions are
     *             suppressed, and classes are not added to the resulting list if loading them exhibits any of these
     *             problems.
     * @return a list of references to the loaded classes.
     */
    public <T> List<Class<T>> classNamesToClassRefs(final List<String> classNames, final Class<T> commonClassType,
            final boolean ignoreExceptions) throws IllegalArgumentException {
        if (classNames.isEmpty()) {
            return Collections.<Class<T>> emptyList();
        } else {
            final List<Class<T>> classRefs = new ArrayList<>();
            // Try loading each class
            for (final String className : classNames) {
                final Class<T> classRef = classNameToClassRef(className, commonClassType, ignoreExceptions);
                if (classRef != null) {
                    classRefs.add(classRef);
                }
            }
            return classRefs.isEmpty() ? Collections.<Class<T>> emptyList() : classRefs;
        }
    }

    /**
     * Produce a list of Class references given a list of class names. If any classes cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block),
     * IllegalArgumentException will be thrown.
     *
     * @param classNames
     *            The list of names of classes to load.
     * @throws IllegalArgumentException
     *             if there were problems loading or initializing the classes. (Note that class initialization on
     *             load is disabled by default, you can enable it with
     *             {@code FastClasspathScanner#initializeLoadedClasses(true)} .)
     * @return a list of references to the loaded classes.
     */
    public List<Class<?>> classNamesToClassRefs(final List<String> classNames) throws IllegalArgumentException {
        return classNamesToClassRefs(classNames, /* ignoreExceptions = */ false);
    }

    /**
     * Produce a list of Class references, given a list of names of classes extending a common superclass or
     * implementing a common interface. If any classes cannot be loaded (due to classloading error, or due to an
     * exception being thrown in the class initialization block), IllegalArgumentException will be thrown.
     *
     * @param classNames
     *            The list of names of classes to load.
     * @param commonClassType
     *            The common superclass of, or interface implemented by, the named classes.
     * @throws IllegalArgumentException
     *             if there were problems loading the classes, initializing the classes, or casting them to the
     *             requested type. (Note that class initialization on load is disabled by default, you can enable it
     *             with {@code FastClasspathScanner#initializeLoadedClasses(true)} .)
     * @return a list of references to the loaded classes.
     */
    public <T> List<Class<T>> classNamesToClassRefs(final List<String> classNames, final Class<T> commonClassType)
            throws IllegalArgumentException {
        return classNamesToClassRefs(classNames, commonClassType, /* ignoreExceptions = */ false);
    }

    /**
     * Produce Class reference given a class name. If ignoreExceptions is false, and the class cannot be loaded (due
     * to classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            The names of the class to load.
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
    public Class<?> classNameToClassRef(final String className, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        try {
            return loadClass(className, /* returnNullIfClassNotFound = */ ignoreExceptions, log);
        } catch (final Throwable e) {
            if (ignoreExceptions) {
                return null;
            } else {
                throw e;
            }
        } finally {
            // Manually flush log, since this method is called after scanning is complete
            if (log != null) {
                log.flush();
            }
        }
    }

    /**
     * Produce Class reference given a class name. If ignoreExceptions is false, and the class cannot be loaded (due
     * to classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            The names of the class to load.
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
    public <T> Class<T> classNameToClassRef(final String className, final Class<T> classType,
            final boolean ignoreExceptions) throws IllegalArgumentException {
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
                throw e;
            }
        } finally {
            // Manually flush log, since this method is called after scanning is complete
            if (log != null) {
                log.flush();
            }
        }
    }

    /**
     * Produce Class reference given a class name. If the class cannot be loaded (due to classloading error, or due
     * to an exception being thrown in the class initialization block), an IllegalArgumentException is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            The names of the classe to load.
     * @throws IllegalArgumentException
     *             if there were problems loading or initializing the class. (Note that class initialization on load
     *             is disabled by default, you can enable it with
     *             {@code FastClasspathScanner#initializeLoadedClasses(true)} .)
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    public Class<?> classNameToClassRef(final String className) throws IllegalArgumentException {
        return classNameToClassRef(className, /* ignoreExceptions = */ false);
    }

    /**
     * Produce Class reference given a class name. If the class cannot be loaded (due to classloading error, or due
     * to an exception being thrown in the class initialization block), an IllegalArgumentException is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            The names of the classe to load.
     * @param classType
     *            The class type to cast the result to.
     * @throws IllegalArgumentException
     *             if there were problems loading the class, initializing the class, or casting it to the requested
     *             type. (Note that class initialization on load is disabled by default, you can enable it with
     *             {@code FastClasspathScanner#initializeLoadedClasses(true)} .)
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    public <T> Class<T> classNameToClassRef(final String className, final Class<T> classType)
            throws IllegalArgumentException {
        return classNameToClassRef(className, classType, /* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The current serialization format. */
    private static final String CURRENT_SERIALIZATION_FORMAT = "3";

    /** A class to hold a serialized ScanResult along with the ScanSpec that was used to scan. */
    private static class SerializationFormat {
        public String serializationFormat;
        public ScanSpec scanSpec;
        public List<ClassInfo> allClassInfo;

        @SuppressWarnings("unused")
        public SerializationFormat() {
        }

        public SerializationFormat(final String serializationFormat, final ScanSpec scanSpec,
                final ClassGraphBuilder classGraphBuilder) {
            this.serializationFormat = serializationFormat;
            this.scanSpec = scanSpec;
            this.allClassInfo = new ArrayList<>(classGraphBuilder.classNameToClassInfo.values());
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
        final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder(deserialized.scanSpec,
                classNameToClassInfo);
        final ScanResult scanResult = new ScanResult(deserialized.scanSpec,
                Collections.<ClasspathElement> emptyList(), envClassLoaderOrder, classGraphBuilder,
                /* fileToLastModified = */ null, /* nestedJarHandler = */ null, /* interruptionChecker = */ null,
                /* log = */ null);
        classGraphBuilder.setFields(deserialized.scanSpec, scanResult);
        return scanResult;
    }

    /**
     * Serialize a ScanResult to JSON.
     * 
     * @param indentWidth
     *            If greater than 0, JSON will be formatted (indented), otherwise it will be minified (un-indented).
     */
    public String toJSON(final int indentWidth) {
        return JSONSerializer.serializeObject(
                new SerializationFormat(CURRENT_SERIALIZATION_FORMAT, scanSpec, classGraphBuilder), indentWidth,
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
        if (nestedJarHandler != null) {
            nestedJarHandler.close(log);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        // TODO: replace this with java.lang.ref.Cleaner once FCS bumps minimum Java version to 10+.
        freeTempFiles(null);
    }
}
