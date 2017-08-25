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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the class graph, and provides methods for querying it. */
class ClassGraphBuilder {
    final Map<String, ClassInfo> classNameToClassInfo;
    private final ScanSpec scanSpec;
    private final Set<ClassInfo> allClassInfo;
    private final Map<String, List<ClassLoader>> classNameToClassLoaders = new HashMap<>();

    /** Builds the class graph, and provides methods for querying it. */
    ClassGraphBuilder(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo) {
        this.scanSpec = scanSpec;
        this.classNameToClassInfo = classNameToClassInfo;
        this.allClassInfo = new HashSet<>(classNameToClassInfo.values());
        for (final ClassInfo classInfo : this.allClassInfo) {
            final List<ClassLoader> classLoaders = classInfo.getClassLoaders();
            if (classLoaders != null) {
                classNameToClassLoaders.put(classInfo.getClassName(), classLoaders);
            }
        }
    }

    /** Get a map from class name to ClassInfo for the class. */
    Map<String, ClassInfo> getClassNameToClassInfo() {
        return classNameToClassInfo;
    }

    /** Get a map from class name to ClassLoader(s) for the class. */
    public Map<String, List<ClassLoader>> getClassNameToClassLoaders() {
        return classNameToClassLoaders;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    List<String> getNamesOfAllClasses() {
        return getNamesOfAllClasses(null);
    }

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan, matching the
     * specified classloader. */
    List<String> getNamesOfAllClasses(ClassLoader classLoader) {
        return ClassInfo.getNamesOfAllClasses(scanSpec, allClassInfo, classLoader);
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    List<String> getNamesOfAllStandardClasses() {
        return getNamesOfAllStandardClasses(null);
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan, matching
     * the specified classloader. */
    List<String> getNamesOfAllStandardClasses(ClassLoader classLoader) {
        return ClassInfo.getNamesOfAllStandardClasses(scanSpec, allClassInfo, classLoader);
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    List<String> getNamesOfSubclassesOf(final String className) {
        return getNamesOfSubclassesOf(className, null);
    }

    /** Return the sorted list of names of all subclasses of the named class, matching the specified classloader. */
    List<String> getNamesOfSubclassesOf(final String className, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSubclasses(classLoader);
        }
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    List<String> getNamesOfSuperclassesOf(final String className) {
        return getNamesOfSuperclassesOf(className, null);
    }

    /** Return the sorted list of names of all superclasses of the named class, matching the specified classloader. */
    List<String> getNamesOfSuperclassesOf(final String className, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSuperclasses(classLoader);
        }
    }

    /**
     * Return a sorted list of classes that have a field of the named type.
     */
    List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        return getNamesOfClassesWithFieldOfType(fieldTypeName, null);
    }

    /**
     * Return a sorted list of classes that have a field of the named type, matching the specified classloader.
     */
    List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName, ClassLoader classLoader) {
        return ClassInfo.getNamesOfClassesWithFieldOfType(fieldTypeName, allClassInfo, classLoader);
    }

    /**
     * Return a sorted list of classes that have a method with the named annotation.
     */
    List<String> getNamesOfClassesWithMethodAnnotation(final String annotationName) {
        return getNamesOfClassesWithMethodAnnotation(annotationName, null);
    }

    /**
     * Return a sorted list of classes that have a method with the named annotation, matching the specified classloader.
     */
    List<String> getNamesOfClassesWithMethodAnnotation(final String annotationName, ClassLoader classLoader) {
        return ClassInfo.getNamesOfClassesWithMethodAnnotation(annotationName, allClassInfo, classLoader);
    }

    /**
     * Return a sorted list of classes that have a field with the named annotation.
     */
    List<String> getNamesOfClassesWithFieldAnnotation(final String annotationName) {
        return getNamesOfClassesWithFieldAnnotation(annotationName, null);
    }

    /**
     * Return a sorted list of classes that have a field with the named annotation, matching the specified classloader.
     */
    List<String> getNamesOfClassesWithFieldAnnotation(final String annotationName, ClassLoader classLoader) {
        return ClassInfo.getNamesOfClassesWithFieldAnnotation(annotationName, allClassInfo, classLoader);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted unique names of all interface classes found during the scan. */
    List<String> getNamesOfAllInterfaceClasses() {
        return getNamesOfAllInterfaceClasses(null);
    }

    /** Return the sorted unique names of all interface classes found during the scan, matching the specified
     * classloader. */
    List<String> getNamesOfAllInterfaceClasses(ClassLoader classLoader) {
        return ClassInfo.getNamesOfAllInterfaceClasses(scanSpec, allClassInfo, classLoader);
    }

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return getNamesOfSubinterfacesOf(interfaceName, null);
    }

    /** Return the sorted list of names of all subinterfaces of the named interface, matching the specified
     * classloader. */
    List<String> getNamesOfSubinterfacesOf(final String interfaceName, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSubinterfaces(classLoader);
        }
    }

    /** Return the names of all superinterfaces of the named interface. */
    List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        return getNamesOfSuperinterfacesOf(interfaceName, null);
    }

    /** Return the names of all superinterfaces of the named interface, matching the specified classloader. */
    List<String> getNamesOfSuperinterfacesOf(final String interfaceName, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSuperinterfaces(classLoader);
        }
    }

    /** Return the sorted list of names of all classes implementing the named interface, and their subclasses. */
    List<String> getNamesOfClassesImplementing(final String interfaceName) {
        return getNamesOfClassesImplementing(interfaceName, null);
    }

    /** Return the sorted list of names of all classes implementing the named interface, and their subclasses,
     * matching the specified classloader. */
    List<String> getNamesOfClassesImplementing(final String interfaceName, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfClassesImplementing(classLoader);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Return the sorted unique names of all annotation classes found during the scan. */
    List<String> getNamesOfAllAnnotationClasses() {
        return ClassInfo.getNamesOfAllAnnotationClasses(scanSpec, allClassInfo);
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with the named class
     * annotation or meta-annotation.
     */
    List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return getNamesOfClassesWithAnnotation(annotationName, null);
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with the named class
     * annotation or meta-annotation, matching the specified classloader.
     */
    List<String> getNamesOfClassesWithAnnotation(final String annotationName, ClassLoader classLoader) {
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfClassesWithAnnotation(classLoader);
        }
    }

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceOrAnnotationName) {
        final ClassInfo classInfo = classNameToClassInfo.get(classOrInterfaceOrAnnotationName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfAnnotations();
        }
    }

    /** Return the sorted list of names of all annotations and meta-annotations on the named annotation. */
    List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfMetaAnnotations();
        }
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        final ClassInfo classInfo = classNameToClassInfo.get(metaAnnotationName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfAnnotationsWithMetaAnnotation();
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class graph visualization

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     */
    String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return ClassInfo.generateClassGraphDotFile(scanSpec, allClassInfo, sizeX, sizeY);
    }
}
