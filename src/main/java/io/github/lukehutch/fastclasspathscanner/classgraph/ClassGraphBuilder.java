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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.ClassType;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.RelType;

public class ClassGraphBuilder {
    private final Map<String, ClassInfo> classNameToClassInfo;
    private final Set<ClassInfo> allClassInfo;

    public ClassGraphBuilder(final Map<String, ClassInfo> classNameToClassInfo) {
        this.classNameToClassInfo = classNameToClassInfo;
        this.allClassInfo = new HashSet<>(classNameToClassInfo.values());

        // Build transitive closures:

        // Classes: --------------------------------------------------------------------------------------------

        // Find all reachable subclasses of each class
        findTransitiveClosure(allClassInfo, RelType.SUPERCLASSES, RelType.SUBCLASSES, RelType.ALL_SUBCLASSES);

        // Find all reachable superclasses of each class (not including java.lang.Object)
        findTransitiveClosure(allClassInfo, RelType.SUBCLASSES, RelType.SUPERCLASSES, RelType.ALL_SUPERCLASSES);

        // Interfaces: -----------------------------------------------------------------------------------------

        // Find all classes implementing a given interface, and all subinterfaces implementing a superinterface 
        findTransitiveClosure(allClassInfo, RelType.IMPLEMENTED_INTERFACES, RelType.CLASSES_IMPLEMENTING,
                RelType.ALL_CLASSES_IMPLEMENTING);

        // Find all superinterfaces of a subinterface
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
    }

    // -------------------------------------------------------------------------------------------------------------
    // Build transitive closure of class graph

    /**
     * Find the transitive closure (reachability) in one direction through the class graph (move in the forward
     * direction, using the backlinks to propagate the set of all reachable back-linked nodes, starting with nodes
     * that are leaves in the backwards direction). Assumes the graph is a DAG in general, but handles cycles too,
     * since these may occur in the case of meta-annotations.
     */
    private static void findTransitiveClosure(final Set<ClassInfo> allNodes, final RelType directForwardLinkType,
            final RelType directBackLinkType, final RelType reachableBackLinkTypeToBuild) {
        // Find extrema of DAG (nodes without direct connections) as initial active set
        Set<ClassInfo> activeNodes = new HashSet<>();
        for (final ClassInfo node : allNodes) {
            final Set<ClassInfo> directBackLinks = node.getRelatedClasses(directBackLinkType);
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
            final Set<ClassInfo> activeNodesNext = new HashSet<>(activeNodes.size());
            for (final ClassInfo node : activeNodes) {
                // Get the direct backlinks
                final Set<ClassInfo> directBackLinks = node.getRelatedClasses(directBackLinkType);
                // Add direct backlinks to the set of reachable backlinked nodes
                boolean changed = node.addRelatedClasses(reachableBackLinkTypeToBuild, directBackLinks);
                // For each direct backlink
                for (final ClassInfo directBackLink : directBackLinks) {
                    // Add the set of reachable backlinked classes for each directly-backlinked class
                    // to the set of reachable backlinked classes for the current node.
                    final Set<ClassInfo> reachableBacklinksOfBacklink = directBackLink
                            .getRelatedClasses(reachableBackLinkTypeToBuild);
                    changed |= node.addRelatedClasses(reachableBackLinkTypeToBuild, reachableBacklinksOfBacklink);
                }
                if (changed) {
                    // If newly-reachable nodes were discovered, add them to the active set for next iteration 
                    final Set<ClassInfo> forwardRelatedClasses = node.getRelatedClasses(directForwardLinkType);
                    activeNodesNext.addAll(forwardRelatedClasses);
                }
            }
            activeNodes = activeNodesNext;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Utility methods

    /**
     * Look up a key, returning the empty list if the map is null, or the key isn't found in the map. Otherwise,
     * optionally filter the list of values associated with the key to remove the names of ClassInfo objects for
     * "external classes", i.e. classes that are referred to by whitelisted classes, but that themselves are not
     * whitelisted (i.e. classes that are neither whitelisted nor blacklisted). (External classes are included in
     * the first place so that you can search by external class names, but result lists only contain whitelisted
     * classes.) Sort and return the optionally filtered list.
     */
    private List<String> getRelatedClassNames(final String className, final RelType relType,
            final boolean removeExternalClasses, final ClassType... classTypes) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? Collections.<String> emptyList()
                : classInfo.getRelatedClassNames(relType, removeExternalClasses, classTypes);
    }

    /**
     * Look up a key, returning the empty list if the map is null, or the key isn't found in the map. Otherwise,
     * optionally filter the list of values associated with the key to remove the names of ClassInfo objects for
     * "external classes", i.e. classes that are referred to by whitelisted classes, but that themselves are not
     * whitelisted (i.e. classes that are neither whitelisted nor blacklisted). (External classes are included in
     * the first place so that you can search by external class names, but result lists only contain whitelisted
     * classes.) Sort and return the optionally filtered list.
     */
    private Set<ClassInfo> getRelatedClasses(final String className, final RelType relType,
            final boolean removeExternalClasses, final ClassType... classTypes) {
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? Collections.<ClassInfo> emptySet()
                : classInfo.getRelatedClasses(relType, removeExternalClasses, classTypes);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    public List<String> getNamesOfAllClasses() {
        return ClassInfo.getClassNamesFiltered(allClassInfo, /* removeExternalClasses = */ true, ClassType.ALL);
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    public List<String> getNamesOfAllStandardClasses() {
        return ClassInfo.getClassNamesFiltered(allClassInfo, /* removeExternalClasses = */ true,
                ClassType.STANDARD_CLASS);
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
        return ClassInfo.getClassNamesFiltered(allClassInfo, /* removeExternalClasses = */ true,
                ClassType.IMPLEMENTED_INTERFACE);
    }

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
        final Set<ClassInfo> implementingClasses = getRelatedClasses(interfaceName,
                RelType.ALL_CLASSES_IMPLEMENTING, /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
        // Subclasses of implementing classes also implement the interface
        final HashSet<ClassInfo> allImplementingClasses = new HashSet<>();
        for (final ClassInfo implementingClass : implementingClasses) {
            allImplementingClasses.add(implementingClass);
            allImplementingClasses.addAll(implementingClass.getRelatedClasses(RelType.ALL_SUBCLASSES));
        }
        return ClassInfo.getClassNames(allImplementingClasses);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Return the sorted unique names of all annotation classes found during the scan. */
    public List<String> getNamesOfAllAnnotationClasses() {
        return ClassInfo.getClassNamesFiltered(allClassInfo, /* removeExternalClasses = */ true,
                ClassType.ANNOTATION);
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with the named class
     * annotation or meta-annotation.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        // Find annotations and meta-annotations on standard classes
        return getRelatedClassNames(annotationName, RelType.ALL_ANNOTATED_CLASSES,
                /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS, ClassType.IMPLEMENTED_INTERFACE);
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
