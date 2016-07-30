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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ClassGraphBuilder {
    private final Map<String, ClassInfo> classNameToClassInfo;
    private final ScanSpec scanSpec;
    private final Set<ClassInfo> allClassInfo;

    ClassGraphBuilder(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo) {
        this.scanSpec = scanSpec;
        this.classNameToClassInfo = classNameToClassInfo;
        this.allClassInfo = new HashSet<>(classNameToClassInfo.values());
    }

    /** Get a map from class name to ClassInfo for the class. */
    Map<String, ClassInfo> getClassNameToClassInfo() {
        return classNameToClassInfo;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    List<String> getNamesOfAllClasses() {
        return ClassInfo.getNamesOfAllClasses(scanSpec, allClassInfo);
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    List<String> getNamesOfAllStandardClasses() {
        return ClassInfo.getNamesOfAllStandardClasses(scanSpec, allClassInfo);
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    List<String> getNamesOfSubclassesOf(final String className) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSubclasses();
        }
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    List<String> getNamesOfSuperclassesOf(final String className) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSuperclasses();
        }
    }

    /**
     * Return a sorted list of classes that have a field of the named type, where the field type is in a whitelisted
     * (non-blacklisted) package.
     */
    List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        return ClassInfo.getNamesOfClassesWithFieldOfType(fieldTypeName, allClassInfo);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted unique names of all interface classes found during the scan. */
    List<String> getNamesOfAllInterfaceClasses() {
        return ClassInfo.getNamesOfAllInterfaceClasses(scanSpec, allClassInfo);
    }

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return null;
        } else {
            return classInfo.getNamesOfSubinterfaces();
        }
    }

    /** Return the names of all superinterfaces of the named interface. */
    List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfSuperinterfaces();
        }
    }

    /** Return the sorted list of names of all classes implementing the named interface, and their subclasses. */
    List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfClassesImplementing();
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
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        if (classInfo == null) {
            return Collections.emptyList();
        } else {
            return classInfo.getNamesOfClassesWithAnnotation();
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
