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
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** The result of a scan. */
public class ScanResult {
    /** The scan spec. */
    final ScanSpec scanSpec;

    /** The order of unique classpath elements. */
    final List<ClasspathElement> classpathOrder;

    /** The order in which ClassLoaders are called to load classes. */
    private final List<ClassLoader> classLoaderOrder;

    /** The list of File objects for unique classpath elements (directories or jarfiles). */
    private final List<File> classpathElementOrderFiles;

    /** The list of URL objects for unique classpath elements (directories or jarfiles). */
    private final List<URL> classpathElementOrderURLs;

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
    final ClassGraphBuilder classGraphBuilder;

    /** Exceptions thrown while loading classes or while calling MatchProcessors on loaded classes. */
    private final List<Throwable> matchProcessorExceptions = new ArrayList<>();

    /** The interruption checker. */
    final InterruptionChecker interruptionChecker;

    /** The log. */
    final LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /** The result of a scan. */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final List<ClassLoader> classLoaderOrder, final ClassGraphBuilder classGraphBuilder,
            final Map<File, Long> fileToLastModified, final NestedJarHandler nestedJarHandler,
            final InterruptionChecker interruptionChecker, final LogNode log) {
        this.scanSpec = scanSpec;
        this.classpathOrder = classpathOrder;
        this.classLoaderOrder = classLoaderOrder;
        this.classpathElementOrderFiles = new ArrayList<>();
        this.classpathElementOrderURLs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            classpathElementOrderFiles.add(classpathElement.getClasspathElementFile());
            classpathElementOrderURLs.add(classpathElement.getClasspathElementURL());
        }
        this.fileToLastModified = fileToLastModified;
        this.classGraphBuilder = classGraphBuilder;
        this.nestedJarHandler = nestedJarHandler;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
    }

    /**
     * Find the classloader(s) for the named class. Typically there will only be one ClassLoader returned. However,
     * if more than one is returned, they should be called in turn until one is able to load the class.
     */
    public List<ClassLoader> getClassLoadersForClass(final String className) {
        final List<ClassLoader> classLoaders = classGraphBuilder.getClassNameToClassLoaders().get(className);
        if (classLoaders != null) {
            return classLoaders;
        } else {
            // Default to default classloader order if classpath element didn't have specified classloader(s)
            return classLoaderOrder;
        }
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
        final StringBuilder buf = new StringBuilder();
        for (final File f : getUniqueClasspathElements()) {
            if (buf.length() > 0) {
                buf.append(File.pathSeparatorChar);
            }
            buf.append(f.toString());
        }
        return buf.toString();
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
        return getNamesOfAllClasses(null);
    }

    /**
     * Get the names of all classes, interfaces and annotations found during the scan, matching the specified
     * classloader.
     *
     * @param classLoader only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all whitelisted classes found during the scan, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAllClasses(ClassLoader classLoader) {
        return classGraphBuilder.getNamesOfAllClasses(classLoader);
    }

    /**
     * Get the names of all standard (non-interface/non-annotation) classes found during the scan.
     * 
     * @return The sorted list of the names of all encountered standard classes, or the empty list if none.
     */
    public List<String> getNamesOfAllStandardClasses() {
        return getNamesOfAllStandardClasses(null);
    }

    /**
     * Get the names of all standard (non-interface/non-annotation) classes found during the scan, matching the
     * specified classloader.
     *
     * @param classLoader only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all encountered standard classes, or the empty list if none.
     */
    public List<String> getNamesOfAllStandardClasses(ClassLoader classLoader) {
        return classGraphBuilder.getNamesOfAllStandardClasses(classLoader);
    }

    /**
     * Get the names of all subclasses of the named class.
     * 
     * @param superclassName
     *            The name of the superclass.
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName) {
        return getNamesOfSubclassesOf(superclassName, null);
    }

    /**
     * Get the names of all subclasses of the named class, matching the specified classloader.
     *
     * @param superclassName
     *            The name of the superclass.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(superclassName);
        return classGraphBuilder.getNamesOfSubclassesOf(superclassName, classLoader);
    }

    /**
     * Get the names of classes on the classpath that extend the specified superclass.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final Class<?> superclass) {
        return getNamesOfSubclassesOf(superclass, null);
    }

    /**
     * Get the names of classes on the classpath that extend the specified superclass, matching the specified
     * classloader.
     *
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of matching subclasses, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final Class<?> superclass, ClassLoader classLoader) {
        return classGraphBuilder.getNamesOfSubclassesOf(scanSpec.getStandardClassName(superclass), classLoader);
    }

    /**
     * Get the names of classes on the classpath that are superclasses of the named subclass.
     * 
     * @param subclassName
     *            The name of the subclass.
     * @return The sorted list of the names of superclasses of the named subclass, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName) {
        return getNamesOfSuperclassesOf(subclassName, null);
    }

    /**
     * Get the names of classes on the classpath that are superclasses of the named subclass, matching the specified
     * classloader.
     *
     * @param subclassName
     *            The name of the subclass.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of superclasses of the named subclass, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(subclassName);
        return classGraphBuilder.getNamesOfSuperclassesOf(subclassName, classLoader);
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
        return getNamesOfSuperclassesOf(subclass, null);
    }

    /**
     * Get the names of classes on the classpath that are superclasses of the specified subclass, matching the
     * specified classloader.
     *
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of superclasses of the subclass, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final Class<?> subclass, ClassLoader classLoader) {
        return getNamesOfSuperclassesOf(scanSpec.getStandardClassName(subclass), classLoader);
    }

    /**
     * Get the names of classes that have a field of the named type, or that have the given type as a type
     * parameter.
     * 
     * @param fieldTypeName
     *            the name of the field type.
     * @return The sorted list of the names of classes with a field of the named type, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        return getNamesOfClassesWithFieldOfType(fieldTypeName, null);
    }

    /**
     * Get the names of classes that have a field of the named type, or that have the given type as a type
     * parameter, matching the specified classloader.
     *
     * @param fieldTypeName
     *            the name of the field type.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes with a field of the named type, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(fieldTypeName);
        if (!scanSpec.enableFieldTypeIndexing) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableFieldTypeIndexing() before calling scan() -- "
                            + "field type indexing is disabled by default for efficiency");
        }
        return classGraphBuilder.getNamesOfClassesWithFieldOfType(fieldTypeName, classLoader);
    }

    /**
     * Get the names of classes that have a field of the given type, or that have the given type as a type
     * parameter.
     * 
     * @param fieldType
     *            the field type.
     * @return The sorted list of the names of classes with a field of the given type, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final Class<?> fieldType) {
        return getNamesOfClassesWithFieldOfType(fieldType, null);
    }

    /**
     * Get the names of classes that have a field of the given type, or that have the given type as a type
     * parameter, matching the specified classloader.
     *
     * @param fieldType
     *            the field type.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes with a field of the given type, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final Class<?> fieldType, ClassLoader classLoader) {
        final String fieldTypeName = fieldType.getName();
        return getNamesOfClassesWithFieldOfType(fieldTypeName, classLoader);
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
        return getNamesOfClassesWithMethodAnnotation(annotationName, null);
    }

    /**
     * Get the names of classes that have a method with an annotation of the named type, matching the specified
     * classloader.
     *
     * @param annotationName
     *            the name of the method annotation.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes with a method that has an annotation of the named type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithMethodAnnotation(final String annotationName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(annotationName);
        if (!scanSpec.enableMethodAnnotationIndexing) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableMethodAnnotationIndexing() before calling scan() -- "
                            + "method annotation indexing is disabled by default for efficiency");
        }
        return classGraphBuilder.getNamesOfClassesWithMethodAnnotation(annotationName, classLoader);
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
        return getNamesOfClassesWithMethodAnnotation(annotation, null);
    }

    /**
     * Get the names of classes that have a method with an annotation of the given type, matching the specified
     * classloader.
     *
     * @param annotation
     *            the method annotation.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes with a method that has an annotation of the given type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithMethodAnnotation(final Class<?> annotation, ClassLoader classLoader) {
        return getNamesOfClassesWithMethodAnnotation(scanSpec.getAnnotationName(annotation), classLoader);
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
        return getNamesOfClassesWithFieldAnnotation(annotationName, null);
    }

    /**
     * Get the names of classes that have a field with an annotation of the named type, matching the specified
     * classloader.
     *
     * @param annotationName
     *            the name of the field annotation.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes that have a field with an annotation of the named type, or
     *         the empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldAnnotation(final String annotationName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(annotationName);
        if (!scanSpec.enableFieldAnnotationIndexing) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableFieldAnnotationIndexing() before calling scan() -- "
                            + "field annotation indexing is disabled by default for efficiency");
        }
        return classGraphBuilder.getNamesOfClassesWithFieldAnnotation(annotationName, classLoader);
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

    /**
     * Get the names of classes that have a field with an annotation of the given type, matching the specified
     * classloader.
     *
     * @param annotation
     *            the field annotation.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of classes that have a field with an annotaton of the given type, or the
     *         empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldAnnotation(final Class<?> annotation, ClassLoader classLoader) {
        return getNamesOfClassesWithFieldAnnotation(scanSpec.getAnnotationName(annotation), classLoader);
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
        return getNamesOfAllInterfaceClasses(null);
    }

    /**
     * Get the names of all interface classes found during the scan, matching the specified classloader.
     *
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all whitelisted interfaces found during the scan, or the empty list
     *         if none.
     */
    public List<String> getNamesOfAllInterfaceClasses(ClassLoader classLoader) {
        return classGraphBuilder.getNamesOfAllInterfaceClasses(classLoader);
    }

    /**
     * Get the names of all subinterfaces of the named interface.
     * 
     * @param interfaceName
     *            The interface name.
     * @return The sorted list of the names of all subinterfaces of the named interface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return getNamesOfSubinterfacesOf(interfaceName, null);
    }

    /**
     * Get the names of all subinterfaces of the named interface, matching the specified classloader.
     *
     * @param interfaceName
     *            The interface name.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all subinterfaces of the named interface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(interfaceName);
        return classGraphBuilder.getNamesOfSubinterfacesOf(interfaceName, classLoader);
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
     * Get the names of interfaces on the classpath that extend a given superinterface, matching the specified
     * classloader.
     *
     * @param superInterface
     *            The superinterface.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of subinterfaces of the given superinterface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final Class<?> superInterface, ClassLoader classLoader) {
        return getNamesOfSubinterfacesOf(scanSpec.getInterfaceName(superInterface), classLoader);
    }

    /**
     * Get the names of all superinterfaces of the named interface.
     * 
     * @param subInterfaceName
     *            The subinterface name.
     * @return The sorted list of the names of superinterfaces of the named subinterface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subInterfaceName) {
        return getNamesOfSuperinterfacesOf(subInterfaceName, null);
    }

    /**
     * Get the names of all superinterfaces of the named interface, matching the specified classloader.
     *
     * @param subInterfaceName
     *            The subinterface name.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of superinterfaces of the named subinterface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subInterfaceName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(subInterfaceName);
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterfaceName, classLoader);
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
     * Get the names of all superinterfaces of the given subinterface, matching the specified classloader.
     *
     * @param subInterface
     *            The subinterface.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of superinterfaces of the given subinterface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final Class<?> subInterface, ClassLoader classLoader) {
        return getNamesOfSuperinterfacesOf(scanSpec.getInterfaceName(subInterface), classLoader);
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
        return getNamesOfClassesImplementing(interfaceName, null);
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) the named interface (or one
     * of its subinterfaces), matching the specified classloader.
     *
     * @param interfaceName
     *            The interface name.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all classes that implement the named interface, or the empty list if
     *         none.
     */
    public List<String> getNamesOfClassesImplementing(final String interfaceName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(interfaceName);
        return classGraphBuilder.getNamesOfClassesImplementing(interfaceName, classLoader);
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
     * Get the names of all classes that implement (or have superclasses that implement) the given interface (or one
     * of its subinterfaces), matching the specified classloader.
     *
     * @param implementedInterface
     *            The interface.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all classes that implement the given interface, or the empty list if
     *         none.
     */
    public List<String> getNamesOfClassesImplementing(final Class<?> implementedInterface, ClassLoader classLoader) {
        return getNamesOfClassesImplementing(scanSpec.getInterfaceName(implementedInterface), classLoader);
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
        return getNamesOfClassesImplementingAllOf(null, implementedInterfaceNames);
    }

    /**
     * Get the names of all classes that implement (or have superclasses that implement) all of the named interfaces
     * (or their subinterfaces), matching the specified classloader.
     *
     * @param implementedInterfaceNames
     *            The names of the interfaces.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all classes that implement all of the named interfaces, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(ClassLoader classLoader,
                                                           final String... implementedInterfaceNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < implementedInterfaceNames.length; i++) {
            final String implementedInterfaceName = implementedInterfaceNames[i];
            final List<String> namesOfImplementingClasses = getNamesOfClassesImplementing(implementedInterfaceName, classLoader);
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

    /**
     * Get the names of all classes that implement (or have superclasses that implement) all of the given interfaces
     * (or their subinterfaces), matching the specified classloader.
     *
     * @param implementedInterfaces
     *            The interfaces.
     * @param classLoader
     *            Only returns interfaces from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all classes that implement all of the given interfaces, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(ClassLoader classLoader,
                                                           final Class<?>... implementedInterfaces) {
        return getNamesOfClassesImplementingAllOf(classLoader, scanSpec.getInterfaceNames(implementedInterfaces));
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
        return getNamesOfClassesWithAnnotation(annotationName, null);
    }

    /**
     * Get the names of non-annotation classes with the named class annotation or meta-annotation, matching the
     * specified classloader.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all non-annotation classes that were found with the named class
     *         annotation during the scan, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName, ClassLoader classLoader) {
        scanSpec.checkClassIsNotBlacklisted(annotationName);
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotationName, classLoader);
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
     * Get the names of non-annotation classes with the given class annotation or meta-annotation, matching the
     * specified classloader.
     *
     * @param annotation
     *            The class annotation or meta-annotation to match.
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     *
     * @return The sorted list of the names of all non-annotation classes that were found with the given class
     *         annotation during the scan, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation, ClassLoader classLoader) {
        return getNamesOfClassesWithAnnotation(scanSpec.getAnnotationName(annotation), classLoader);
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
        return getNamesOfClassesWithAnnotationsAllOf(null, annotationNames);
    }

    /**
     * Get the names of classes that have all of the named annotations, matching the specified classloader.
     *
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     * @param annotationNames
     *            The class annotation names.
     *
     * @return The sorted list of the names of classes that have all of the named class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(ClassLoader classLoader, final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < annotationNames.length; i++) {
            final String annotationName = annotationNames[i];
            final List<String> namesOfClassesWithMetaAnnotation = getNamesOfClassesWithAnnotation(annotationName, classLoader);
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
     * Get the names of classes that have all of the given annotations, matching the specified classloader.
     *
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     * @param annotations
     *            The class annotations.
     * @return The sorted list of the names of classes that have all of the given class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(ClassLoader classLoader, final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAllOf(classLoader, scanSpec.getAnnotationNames(annotations));
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
        return getNamesOfClassesWithAnnotationsAnyOf(null, annotationNames);
    }

    /**
     * Get the names of classes that have any of the named annotations, matching the specified classloader.
     *
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     * @param annotationNames
     *            The annotation names.
     * @return The sorted list of the names of classes that have any of the named class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(ClassLoader classLoader, final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (final String annotationName : annotationNames) {
            classNames.addAll(getNamesOfClassesWithAnnotation(annotationName, classLoader));
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
     * Get the names of classes that have any of the given annotations, matching the specified classloader.
     *
     * @param classLoader
     *            Only returns classes from this classloader, null for skipping the matching
     * @param annotations
     *            The annotations.
     * @return The sorted list of the names of classes that have any of the given class annotations, or the empty
     *         list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(ClassLoader classLoader, final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAnyOf(classLoader, scanSpec.getAnnotationNames(annotations));
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
        scanSpec.checkClassIsNotBlacklisted(className);
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
        scanSpec.checkClassIsNotBlacklisted(metaAnnotationName);
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
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @return the GraphViz file contents.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return classGraphBuilder.generateClassGraphDotFile(sizeX, sizeY);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Produce a list of Class references given a list of class names. If ignoreExceptions is true, and any classes
     * cannot be loaded (due to classloading error, or due to an exception being thrown in the class initialization
     * block), an IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is
     * thrown.
     * 
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     * 
     * @param classNames
     *            The list of names of classes to load.
     * @param ignoreExceptions
     *            If true, exceptions are ignored during classloading, otherwise IllegalArgumentException is thrown
     *            if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception is thrown during classloading or class
     *             initialization. (Note that class initialization on load is not enabled by default, you can enable
     *             it with FastClasspathScanner#initializeLoadedClasses(true).)
     * @return a list of references to the loaded classes.
     */
    public List<Class<?>> classNamesToClassRefs(final List<String> classNames, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        try {
            if (classNames.isEmpty()) {
                return Collections.<Class<?>> emptyList();
            } else {
                final List<Class<?>> classRefs = new ArrayList<>();
                // Try loading each class
                for (final String className : classNames) {
                    final Class<?> classRef = classNameToClassRef(className);
                    if (classRef != null) {
                        classRefs.add(classRef);
                    }
                }
                return classRefs.isEmpty() ? Collections.<Class<?>> emptyList() : classRefs;
            }
        } finally {
            // Manually flush log, since this method is called after scanning is complete
            if (log != null) {
                log.flush();
            }
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
     *             an exception is thrown during classloading or class initialization. (Note that class
     *             initialization on load is not enabled by default, you can enable it with
     *             FastClasspathScanner#initializeLoadedClasses(true).)
     * @return a list of references to the loaded classes.
     */
    public List<Class<?>> classNamesToClassRefs(final List<String> classNames) throws IllegalArgumentException {
        return classNamesToClassRefs(classNames, /* ignoreExceptions = */ false);
    }

    /**
     * Produce Class reference given a class name. If ignoreExceptions is false, and the class cannot be loaded (due
     * to classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     * 
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     * 
     * @param classNames
     *            The list of names of classes to load.
     * @param ignoreExceptions
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception is thrown during classloading or class
     *             initialization. (Note that class initialization on load is not enabled by default, you can enable
     *             it with FastClasspathScanner#initializeLoadedClasses(true).)
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    public Class<?> classNameToClassRef(final String className, final boolean ignoreExceptions)
            throws IllegalArgumentException {
        try {
            if (className == null || className.isEmpty()) {
                if (!ignoreExceptions) {
                    throw new IllegalArgumentException("Class names cannot be null or empty");
                } else {
                    return null;
                }
            } else {
                if (ignoreExceptions) {
                    try {
                        return scanSpec.loadClass(className, this, log);
                    } catch (final IllegalArgumentException e) {
                        // Ignore exceptions
                        return null;
                    }
                } else {
                    return scanSpec.loadClass(className, this, log);
                }
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
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     * 
     * @param classNames
     *            The list of names of classes to load.
     * @throws IllegalArgumentException
     *             if an exception is thrown during classloading or class initialization. (Note that class
     *             initialization on load is not enabled by default, you can enable it with
     *             FastClasspathScanner#initializeLoadedClasses(true).)
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    public Class<?> classNameToClassRef(final String className) throws IllegalArgumentException {
        return classNameToClassRef(className, /* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Free any temporary files created by extracting jars from within jars. By default, temporary files are removed
     * at the end of a scan, after MatchProcessors have completed, so this typically does not need to be called.
     */
    public void freeTempFiles(final LogNode log) {
        nestedJarHandler.close(log);
    }
}
