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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo.ClassType;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo.RelType;

class ClassGraphBuilder {
    private final Map<String, ClassInfo> classNameToClassInfo;
    private final Set<ClassInfo> allClassInfo;

    ClassGraphBuilder(final Map<String, ClassInfo> classNameToClassInfo) {
        this.classNameToClassInfo = classNameToClassInfo;
        this.allClassInfo = new HashSet<>(classNameToClassInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Find the transitive closure from a named node in the class graph

    /**
     * Find all classes reachable from the named start class (not including the start class itself), given a certain
     * relationship type.
     */
    private Set<ClassInfo> getReachableClasses(final String startClassName, final RelType relType) {
        final ClassInfo startClass = classNameToClassInfo.get(startClassName);
        return startClass == null ? Collections.<ClassInfo> emptySet() : startClass.getReachableClasses(relType);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    public List<String> getNamesOfAllClasses() {
        return ClassInfo.getClassNames(
                ClassInfo.filterClassInfo(allClassInfo, /* removeExternalClasses = */ true, ClassType.ALL));
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    public List<String> getNamesOfAllStandardClasses() {
        return ClassInfo.getClassNames(ClassInfo.filterClassInfo(allClassInfo, /* removeExternalClasses = */ true,
                ClassType.STANDARD_CLASS));
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    List<String> getNamesOfSubclassesOf(final String className) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(className, RelType.SUBCLASSES),
                        /* removeExternalClasses = */ true, ClassType.ALL));
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    List<String> getNamesOfSuperclassesOf(final String className) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(className, RelType.SUPERCLASSES),
                        /* removeExternalClasses = */ true, ClassType.ALL));
    }

    /**
     * Return a sorted list of classes that have a field of the named type, where the field type is in a whitelisted
     * (non-blacklisted) package.
     */
    List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        // This method will not likely be used for a large number of different field types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithFieldOfType = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo fieldType : classInfo.getRelatedClasses(RelType.FIELD_TYPES)) {
                if (fieldType.className.equals(fieldTypeName)) {
                    namesOfClassesWithFieldOfType.add(classInfo.className);
                    break;
                }
            }
        }
        Collections.sort(namesOfClassesWithFieldOfType);
        return namesOfClassesWithFieldOfType;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return ClassInfo.getClassNames(ClassInfo.filterClassInfo(allClassInfo, /* removeExternalClasses = */ true,
                ClassType.IMPLEMENTED_INTERFACE));
    }

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(interfaceName, RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE));
    }

    /** Return the names of all superinterfaces of the named interface. */
    List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(interfaceName, RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE));
    }

    /** Return the sorted list of names of all classes implementing the named interface. */
    List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final Set<ClassInfo> implementingClasses = ClassInfo.filterClassInfo(
                getReachableClasses(interfaceName, RelType.CLASSES_IMPLEMENTING),
                /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
        // Subclasses of implementing classes also implement the interface
        final Set<ClassInfo> allImplementingClasses = new HashSet<>();
        for (final ClassInfo implementingClass : implementingClasses) {
            allImplementingClasses.add(implementingClass);
            allImplementingClasses.addAll(implementingClass.getReachableClasses(RelType.SUBCLASSES));
        }
        return ClassInfo.getClassNames(allImplementingClasses);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Return the sorted unique names of all annotation classes found during the scan. */
    public List<String> getNamesOfAllAnnotationClasses() {
        return ClassInfo.getClassNames(
                ClassInfo.filterClassInfo(allClassInfo, /* removeExternalClasses = */ true, ClassType.ANNOTATION));
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with the named class
     * annotation or meta-annotation.
     */
    List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(annotationName, RelType.ANNOTATED_CLASSES),
                        /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS,
                        ClassType.IMPLEMENTED_INTERFACE));
    }

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(classOrInterfaceName, RelType.ANNOTATIONS),
                        /* removeExternalClasses = */ true, ClassType.ALL));
    }

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return getNamesOfAnnotationsOnClass(annotationName);
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return ClassInfo.getClassNames( //
                ClassInfo.filterClassInfo(getReachableClasses(metaAnnotationName, RelType.ANNOTATED_CLASSES),
                        /* removeExternalClasses = */ true, ClassType.ANNOTATION));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class graph visualization

    /**
     * Splits a .dot node label into two text lines, putting the package on one line and the class name on the next.
     */
    private static String label(final ClassInfo node) {
        final String className = node.className;
        final int dotIdx = className.lastIndexOf('.');
        if (dotIdx < 0) {
            return className;
        }
        return className.substring(0, dotIdx + 1) + "\\n" + className.substring(dotIdx + 1);
    }

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     */
    String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        final StringBuilder buf = new StringBuilder();
        buf.append("digraph {\n");
        buf.append("size=\"" + sizeX + "," + sizeY + "\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");

        final Set<ClassInfo> standardClassNodes = ClassInfo.filterClassInfo(allClassInfo,
                /* removeExternalClasses = */ false, ClassType.STANDARD_CLASS);
        final Set<ClassInfo> interfaceNodes = ClassInfo.filterClassInfo(allClassInfo,
                /* removeExternalClasses = */ false, ClassType.IMPLEMENTED_INTERFACE);
        final Set<ClassInfo> annotationNodes = ClassInfo.filterClassInfo(allClassInfo,
                /* removeExternalClasses = */ false, ClassType.ANNOTATION);

        buf.append("\nnode[shape=box,style=filled,fillcolor=\"#fff2b6\"];\n");
        for (final ClassInfo node : standardClassNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=diamond,style=filled,fillcolor=\"#b6e7ff\"];\n");
        for (final ClassInfo node : interfaceNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=oval,style=filled,fillcolor=\"#f3c9ff\"];\n");
        for (final ClassInfo node : annotationNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\n");
        for (final ClassInfo classNode : standardClassNodes) {
            for (final ClassInfo superclassNode : classNode.getRelatedClasses(RelType.SUPERCLASSES)) {
                // class --> superclass
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(superclassNode) + "\"\n");
            }
            for (final ClassInfo implementedInterfaceNode : classNode
                    .getRelatedClasses(RelType.IMPLEMENTED_INTERFACES)) {
                // class --<> implemented interface
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(implementedInterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
            for (final ClassInfo fieldTypeNode : classNode.getRelatedClasses(RelType.FIELD_TYPES)) {
                // class --[] whitelisted field type
                buf.append("  \"" + label(fieldTypeNode) + "\" -> \"" + label(classNode)
                        + "\" [arrowtail=obox, dir=back]\n");
            }
        }
        for (final ClassInfo interfaceNode : interfaceNodes) {
            for (final ClassInfo superinterfaceNode : interfaceNode
                    .getRelatedClasses(RelType.IMPLEMENTED_INTERFACES)) {
                // interface --> superinterface
                buf.append("  \"" + label(interfaceNode) + "\" -> \"" + label(superinterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
        }
        for (final ClassInfo annotationNode : annotationNodes) {
            for (final ClassInfo metaAnnotationNode : annotationNode.getRelatedClasses(RelType.ANNOTATIONS)) {
                // annotation --o meta-annotation
                buf.append("  \"" + label(annotationNode) + "\" -> \"" + label(metaAnnotationNode)
                        + "\" [arrowhead=dot]\n");
            }
            for (final ClassInfo annotatedClassNode : annotationNode.getRelatedClasses(RelType.ANNOTATIONS)) {
                // annotated class --o annotation
                buf.append("  \"" + label(annotatedClassNode) + "\" -> \"" + label(annotationNode)
                        + "\" [arrowhead=dot]\n");
            }
        }
        buf.append("}");
        return buf.toString();
    }
}
