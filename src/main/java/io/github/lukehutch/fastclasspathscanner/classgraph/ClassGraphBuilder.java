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
package io.github.lukehutch.fastclasspathscanner.classgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.ClassType;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.RelType;

public class ClassGraphBuilder {
    private final HashMap<String, ClassInfo> classNameToClassInfo;
    private final HashMap<String, ArrayList<ClassInfo>> fieldTypeToContainingClassClassInfo = new HashMap<>();

    public ClassGraphBuilder(final HashMap<String, ClassInfo> classNameToClassInfo) {
        this.classNameToClassInfo = classNameToClassInfo;
        final ArrayList<ClassInfo> allClassInfo = new ArrayList<>(classNameToClassInfo.values());

        // Classes: --------------------------------------------------------------------------------------------

        // Find all reachable subclasses of each class
        findTransitiveClosure(allClassInfo, RelType.SUPERCLASSES, RelType.SUBCLASSES, RelType.ALL_SUBCLASSES);

        // Find all reachable superclasses of each class (not including java.lang.Object)
        findTransitiveClosure(allClassInfo, RelType.SUBCLASSES, RelType.SUPERCLASSES, RelType.ALL_SUPERCLASSES);

        // Interfaces: -----------------------------------------------------------------------------------------

        // Find all classes implementing a given interface, and all subinterfaces implementing a superinterface 
        findTransitiveClosure(allClassInfo, RelType.IMPLEMENTED_INTERFACES, RelType.CLASSES_IMPLEMENTING,
                RelType.ALL_CLASSES_IMPLEMENTING);

        // Find all superinterfaces implementing a subinterface
        findTransitiveClosure(allClassInfo, RelType.CLASSES_IMPLEMENTING, RelType.IMPLEMENTED_INTERFACES,
                RelType.ALL_IMPLEMENTED_INTERFACES);

        // Annotations: ----------------------------------------------------------------------------------------

        // N.B. don't need to propagate annotations to/from sub-classes, as with interfaces above, because
        // regular Java annotations don't get passed from classes to sub-classes the way interfaces do.
        // So even though we do pass meta-annotations to their meta-meta-annotated annotations, once we
        // hit the regular class hierarchy, we stop at the first annotated class.

        findTransitiveClosure(allClassInfo, RelType.ANNOTATIONS, RelType.ANNOTATED_CLASSES,
                RelType.ALL_ANNOTATED_CLASSES);

        findTransitiveClosure(allClassInfo, RelType.ANNOTATED_CLASSES, RelType.ANNOTATIONS,
                RelType.ALL_ANNOTATIONS);

        // Postprocessing: -------------------------------------------------------------------------------------

        for (final ClassInfo classInfo : allClassInfo) {
            // Create a bridge between interface and class hierarchy: when a class implements an interface,
            // it also implements all the interface's super-interfaces, and so do all of its subclasses.
            if (classInfo.isImplementedInterface()) {
                for (final ClassInfo classImplementing : classInfo
                        .getRelatedClasses(RelType.CLASSES_IMPLEMENTING)) {
                    final List<ClassInfo> allSuperInterfaces = classInfo
                            .getRelatedClasses(RelType.ALL_IMPLEMENTED_INTERFACES);
                    final List<ClassInfo> allSubClasses = classImplementing
                            .getRelatedClasses(RelType.ALL_SUBCLASSES);
                    for (final ClassInfo superInterface : allSuperInterfaces) {
                        superInterface.addRelatedClass(RelType.ALL_CLASSES_IMPLEMENTING, classImplementing);
                        classImplementing.addRelatedClass(RelType.ALL_IMPLEMENTED_INTERFACES, superInterface);
                    }
                    for (final ClassInfo subClass : allSubClasses) {
                        classInfo.addRelatedClass(RelType.ALL_CLASSES_IMPLEMENTING, subClass);
                        subClass.addRelatedClass(RelType.ALL_IMPLEMENTED_INTERFACES, classInfo);
                    }
                    for (final ClassInfo superInterface : allSuperInterfaces) {
                        for (final ClassInfo subClass : allSubClasses) {
                            superInterface.addRelatedClass(RelType.ALL_CLASSES_IMPLEMENTING, subClass);
                            subClass.addRelatedClass(RelType.ALL_IMPLEMENTED_INTERFACES, superInterface);
                        }
                    }
                }
            }

            // Split classes that implement an interface into subinterfaces and standard classes.
            for (final ClassInfo implementingClass : classInfo
                    .getRelatedClasses(RelType.ALL_CLASSES_IMPLEMENTING)) {
                if (implementingClass.isImplementedInterface()) {
                    classInfo.addRelatedClass(RelType.ALL_SUBINTERFACES, implementingClass);
                } else if (implementingClass.isStandardClass()) {
                    classInfo.addRelatedClass(RelType.ALL_STANDARD_CLASSES_IMPLEMENTING, implementingClass);
                }
            }

            // Find super-classes and implemented interfaces for all field types, and add those to
            // the set of field types, so that you can search for field types using supertypes.
            final List<ClassInfo> fieldTypes = classInfo.getRelatedClasses(RelType.FIELD_TYPES);
            if (!fieldTypes.isEmpty()) {
                final HashSet<ClassInfo> expandedFieldTypes = new HashSet<>();
                for (final ClassInfo fieldType : fieldTypes) {
                    expandedFieldTypes.addAll(fieldType.getRelatedClasses(RelType.ALL_SUPERCLASSES));
                    expandedFieldTypes.addAll(fieldType.getRelatedClasses(RelType.ALL_IMPLEMENTED_INTERFACES));
                }
                classInfo.addRelatedClasses(RelType.FIELD_TYPES, expandedFieldTypes);
            }

            // Find annotations and meta-annotations on standard classes
            for (final ClassInfo classWithAnnotation : classInfo.getRelatedClasses(RelType.ALL_ANNOTATED_CLASSES)) {
                if (!classWithAnnotation.isAnnotation()) {
                    classInfo.addRelatedClass(RelType.ALL_ANNOTATED_STANDARD_CLASSES_OR_INTERFACES,
                            classWithAnnotation);
                }
            }
        }

        // Create reverse index from field types to classes with fields of that type
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo fieldType : classInfo.getRelatedClasses(RelType.FIELD_TYPES)) {
                ArrayList<ClassInfo> containingClassClassInfo = fieldTypeToContainingClassClassInfo
                        .get(fieldType.className);
                if (containingClassClassInfo == null) {
                    fieldTypeToContainingClassClassInfo.put(fieldType.className,
                            containingClassClassInfo = new ArrayList<>());
                }
                containingClassClassInfo.add(classInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Build transitive closure of class graph

    /**
     * Find the transitive closure (reachability) in one direction through the class graph (move in the forward
     * direction, using the backlinks to propagate the set of all reachable back-linked nodes, starting with nodes
     * that are leaves in the backwards direction). Assumes the graph is a DAG in general, but handles cycles too,
     * since these may occur in the case of meta-annotations.
     */
    private static void findTransitiveClosure(final ArrayList<ClassInfo> allNodes,
            final RelType directForwardLinkType, final RelType directBackLinkType,
            final RelType reachableBackLinkTypeToBuild) {
        // Find extrema of DAG (nodes without direct connections) as initial active set
        HashSet<ClassInfo> activeNodes = new HashSet<>();
        for (final ClassInfo node : allNodes) {
            final List<ClassInfo> directBackLinks = node.getRelatedClasses(directBackLinkType);
            if (directBackLinks.isEmpty()) {
                // There are no back-links from this node, so it is an extremum;
                // add all forward-linked nodes to the active set
                activeNodes.addAll(node.getRelatedClasses(directForwardLinkType));
            }
        }
        // Use DP-style "wavefront" to find transitive closure (i.e. all reachable nodes through back-links),
        // also handling cycles if present.
        while (!activeNodes.isEmpty()) {
            // For each active node, propagate reachability, adding to the next set of active nodes
            // if the set of reachable classes changes for any class.
            final HashSet<ClassInfo> activeNodesNext = new HashSet<>(activeNodes.size());
            for (final ClassInfo node : activeNodes) {
                // Get the direct backlinks
                final List<ClassInfo> directBackLinks = node.getRelatedClasses(directBackLinkType);
                // Add direct backlinks to the set of reachable backlinked nodes
                boolean changed = node.addRelatedClasses(reachableBackLinkTypeToBuild, directBackLinks);
                // For each direct backlink
                for (final ClassInfo directBackLink : directBackLinks) {
                    // Add the set of reachable backlinked classes for each directly-backlinked class
                    // to the set of reachable backlinked classes for the current node.
                    final List<ClassInfo> reachableBacklinksOfBacklink = directBackLink
                            .getRelatedClasses(reachableBackLinkTypeToBuild);
                    changed |= node.addRelatedClasses(reachableBackLinkTypeToBuild, reachableBacklinksOfBacklink);
                }
                if (changed) {
                    // If newly-reachable nodes were discovered, add them to the active set for next iteration 
                    final List<ClassInfo> forwardRelatedClasses = node.getRelatedClasses(directForwardLinkType);
                    activeNodesNext.addAll(forwardRelatedClasses);
                }
            }
            activeNodes = activeNodesNext;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Utility method

    /**
     * Look up a key, returning the empty list if the map is null, or the key isn't found in the map. Otherwise,
     * optionally filter the list of values associated with the key to remove the names of ClassInfo objects for
     * "external classes", i.e. classes that are referred to by whitelisted classes, but that themselves are not
     * whitelisted (i.e. classes that are neither whitelisted nor blacklisted). (External classes are included in
     * the first place so that you can search by external class names, but result lists only contain whitelisted
     * classes.) Sort and return the optionally filtered list.
     */
    private List<String> getRelatedClassNames(final String className, final RelType relType,
            final boolean removeExternalClasses, final ClassType classType) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? Collections.<String> emptyList()
                : classInfo.getRelatedClassNames(relType, removeExternalClasses, classType);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    public List<String> getNamesOfAllClasses() {
        return ClassInfo.getClassNamesFiltered(classNameToClassInfo.values(), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    public List<String> getNamesOfAllStandardClasses() {
        return ClassInfo.getClassNamesFiltered(classNameToClassInfo.values(), /* removeExternalClasses = */ true,
                ClassType.STANDARD_CLASS);
    }

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return ClassInfo.getClassNamesFiltered(classNameToClassInfo.values(), /* removeExternalClasses = */ true,
                ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the sorted unique names of all annotation classes found during the scan. */
    public List<String> getNamesOfAllAnnotationClasses() {
        return ClassInfo.getClassNamesFiltered(classNameToClassInfo.values(), /* removeExternalClasses = */ true,
                ClassType.ANNOTATION);
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        return getRelatedClassNames(className, RelType.ALL_SUBCLASSES, /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        return getRelatedClassNames(className, RelType.ALL_SUPERCLASSES, /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /**
     * Return a sorted list of classes that have a field of the named type, where the field type is in a whitelisted
     * (non-blacklisted) package.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        final ArrayList<ClassInfo> containingClassClassInfo = fieldTypeToContainingClassClassInfo
                .get(fieldTypeName);
        if (containingClassClassInfo == null) {
            return Collections.emptyList();
        }
        return ClassInfo.getClassNamesFiltered(containingClassClassInfo, /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return getRelatedClassNames(interfaceName, RelType.ALL_CLASSES_IMPLEMENTING,
                /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        return getRelatedClassNames(interfaceName, RelType.ALL_IMPLEMENTED_INTERFACES,
                /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the sorted list of names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        return getRelatedClassNames(interfaceName, RelType.ALL_CLASSES_IMPLEMENTING,
                /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Return the sorted list of names of all classes or interfaces with the named class annotation or
     * meta-annotation.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return getRelatedClassNames(annotationName, RelType.ALL_ANNOTATED_STANDARD_CLASSES_OR_INTERFACES,
                /* removeExternalClasses = */ true, ClassType.ALL);
    }

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return getRelatedClassNames(classOrInterfaceName, RelType.ALL_ANNOTATIONS,
                /* removeExternalClasses = */ true, ClassType.ALL);
    }

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return getRelatedClassNames(annotationName, RelType.ALL_ANNOTATIONS, /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return getRelatedClassNames(metaAnnotationName, RelType.ALL_ANNOTATIONS, /* removeExternalClasses = */ true,
                ClassType.ANNOTATION);
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
    public String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        final StringBuilder buf = new StringBuilder();
        buf.append("digraph {\n");
        buf.append("size=\"" + sizeX + "," + sizeY + "\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");

        final Collection<ClassInfo> allClassInfo = classNameToClassInfo.values();
        final List<ClassInfo> standardClassNodes = ClassInfo.filterClassInfo(allClassInfo,
                /* removeExternalClasses = */ false, ClassType.STANDARD_CLASS);
        final List<ClassInfo> interfaceNodes = ClassInfo.filterClassInfo(allClassInfo,
                /* removeExternalClasses = */ false, ClassType.IMPLEMENTED_INTERFACE);
        final List<ClassInfo> annotationNodes = ClassInfo.filterClassInfo(allClassInfo,
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
            for (final ClassInfo superclassNode : classNode.getRelatedClasses(RelType.SUPERCLASSES,
                    /* removeExternalClasses = */ false, ClassType.ALL)) {
                // class --> superclass
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(superclassNode) + "\"\n");
            }
            for (final ClassInfo implementedInterfaceNode : classNode.getRelatedClasses(
                    RelType.IMPLEMENTED_INTERFACES, /* removeExternalClasses = */ false, ClassType.ALL)) {
                // class --<> implemented interface
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(implementedInterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
            for (final ClassInfo fieldTypeNode : classNode.getRelatedClasses(RelType.FIELD_TYPES,
                    /* removeExternalClasses = */ false, ClassType.ALL)) {
                // class --[] whitelisted field type
                buf.append("  \"" + label(fieldTypeNode) + "\" -> \"" + label(classNode)
                        + "\" [arrowtail=obox, dir=back]\n");
            }
        }
        for (final ClassInfo interfaceNode : interfaceNodes) {
            for (final ClassInfo superinterfaceNode : interfaceNode.getRelatedClasses(
                    RelType.IMPLEMENTED_INTERFACES, /* removeExternalClasses = */ false, ClassType.ALL)) {
                // interface --> superinterface
                buf.append("  \"" + label(interfaceNode) + "\" -> \"" + label(superinterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
        }
        for (final ClassInfo annotationNode : annotationNodes) {
            for (final ClassInfo metaAnnotationNode : annotationNode.getRelatedClasses(RelType.ANNOTATIONS,
                    /* removeExternalClasses = */ false, ClassType.ALL)) {
                // annotation --o meta-annotation
                buf.append("  \"" + label(annotationNode) + "\" -> \"" + label(metaAnnotationNode)
                        + "\" [arrowhead=dot]\n");
            }
            for (final ClassInfo annotatedClassNode : annotationNode.getRelatedClasses(RelType.ANNOTATIONS,
                    /* removeExternalClasses = */ false, ClassType.ALL)) {
                // annotated class --o annotation
                buf.append("  \"" + label(annotatedClassNode) + "\" -> \"" + label(annotationNode)
                        + "\" [arrowhead=dot]\n");
            }
        }
        buf.append("}");
        return buf.toString();
    }
}
