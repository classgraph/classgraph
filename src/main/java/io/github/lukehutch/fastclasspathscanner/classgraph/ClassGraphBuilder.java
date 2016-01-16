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

import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ClassGraphBuilder {
    private final ArrayList<StandardClassDAGNode> standardClassNodes = new ArrayList<>();
    private final ArrayList<InterfaceDAGNode> interfaceNodes = new ArrayList<>();
    private final ArrayList<AnnotationDAGNode> annotationNodes = new ArrayList<>();
    private final HashMap<String, DAGNode> classNameToDAGNode = new HashMap<>();
    private final HashSet<String> blacklistedExternalClassNames = new HashSet<>();

    public ClassGraphBuilder(final Collection<ClassInfo> classInfoFromScan, final ScanSpec scanSpec) {
        // Take care of Scala quirks
        final ArrayList<ClassInfo> allClassInfo = new ArrayList<>(Utils.mergeScalaAuxClasses(classInfoFromScan));

        // Create placeholder DAGNodes for all "external" classes that are referenced but don't occur in a
        // whitelisted class, so that when creating DAGNodes for whitelisted classes below, they can connect
        // themselves to these placeholders. Without this, the call to node.connect(classNameToDAGNode)
        // will not be able to find a DAGNode for external referenced classes, because there was no ClassInfo
        // object if a class' classfile binary was never read, because the class was not in a whitelisted
        // package.
        final HashSet<String> externalSuperclasses = new HashSet<>();
        final HashSet<String> externalInterfaces = new HashSet<>();
        final HashSet<String> externalAnnotations = new HashSet<>();
        final ArrayList<String> scannedClasses = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            if (classInfo.superclassNames != null) {
                externalSuperclasses.addAll(classInfo.superclassNames);
            }
            if (classInfo.interfaceNames != null) {
                externalInterfaces.addAll(classInfo.interfaceNames);
            }
            if (classInfo.annotationNames != null) {
                externalAnnotations.addAll(classInfo.annotationNames);
            }
            scannedClasses.add(classInfo.className);
        }
        externalSuperclasses.removeAll(scannedClasses);
        externalInterfaces.removeAll(scannedClasses);
        externalAnnotations.removeAll(scannedClasses);
        // If an external annotation is also extended as an interface, just display it as an interface
        // in graph visualizations
        externalAnnotations.removeAll(externalInterfaces);

        // Create placeholder nodes for external classes, and find non-whitelisted external classes,
        // which should never be returned to the user in a result list, but can be used for matching. 
        for (final String externalSuperclassName : externalSuperclasses) {
            final StandardClassDAGNode newNode = new StandardClassDAGNode(externalSuperclassName);
            classNameToDAGNode.put(externalSuperclassName, newNode);
            standardClassNodes.add(newNode);
            if (!scanSpec.classIsNotBlacklisted(externalSuperclassName)) {
                blacklistedExternalClassNames.add(externalSuperclassName);
            }
        }
        for (final String externalInterfaceName : externalInterfaces) {
            final InterfaceDAGNode newNode = new InterfaceDAGNode(externalInterfaceName);
            classNameToDAGNode.put(externalInterfaceName, newNode);
            interfaceNodes.add(newNode);
            if (!scanSpec.classIsNotBlacklisted(externalInterfaceName)) {
                blacklistedExternalClassNames.add(externalInterfaceName);
            }
        }
        for (final String externalAnnotationName : externalAnnotations) {
            final AnnotationDAGNode newNode = new AnnotationDAGNode(externalAnnotationName);
            classNameToDAGNode.put(externalAnnotationName, newNode);
            annotationNodes.add(newNode);
            if (!scanSpec.classIsNotBlacklisted(externalAnnotationName)) {
                blacklistedExternalClassNames.add(externalAnnotationName);
            }
        }

        // Create DAG node for each class
        for (final ClassInfo classInfo : allClassInfo) {
            final String className = classInfo.className;
            if (classInfo.isAnnotation) {
                final AnnotationDAGNode newNode = new AnnotationDAGNode(classInfo);
                classNameToDAGNode.put(className, newNode);
                annotationNodes.add(newNode);
            } else if (classInfo.isInterface) {
                final InterfaceDAGNode newNode = new InterfaceDAGNode(classInfo);
                classNameToDAGNode.put(className, newNode);
                interfaceNodes.add(newNode);
            } else {
                final StandardClassDAGNode newNode = new StandardClassDAGNode(classInfo);
                classNameToDAGNode.put(className, newNode);
                standardClassNodes.add(newNode);
            }
        }

        // Connect DAG nodes based on class inter-connectedness
        for (final DAGNode node : classNameToDAGNode.values()) {
            node.connect(classNameToDAGNode);
        }

        // Find transitive closure of DAG nodes for each of the class types
        DAGNode.findTransitiveClosure(standardClassNodes);
        DAGNode.findTransitiveClosure(interfaceNodes);
        DAGNode.findTransitiveClosure(annotationNodes);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Utility methods

    /**
     * Wrap a lazy multimap so that when a key is looked up, if there is no correspoding value, the empty list is
     * returned. Otherwise, filter the list of values associated with the key to remove the names of placeholder
     * DAGNodes (indicating a class was not itself in a whitelisted package, but was referenced by a class in a
     * whitelisted package). Dedup, sort and return the filtered list.
     */
    private LazyMap<String, List<String>> sortedNonNullWithoutPlaceholders( //
            final LazyMap<String, ? extends Collection<String>> underlyingMap) {
        return new LazyMap<String, List<String>>() {
            @Override
            public List<String> generateValue(final String key) {
                final Collection<String> classNameList = underlyingMap.get(key);
                if (classNameList == null) {
                    // Return empty list if the map contains no value for a given key.
                    return Collections.emptyList();
                } else {
                    // Filter out placeholder nodes -- need to look up each class in the list by name to see
                    // if it was a placeholder.
                    final HashSet<String> listWithoutPlaceholders = new HashSet<>();
                    for (final String className : classNameList) {
                        // Strip out names of "placeholder nodes", DAGNodes created to hold the place of
                        // an external class that is blacklisted and not whitelisted.
                        if (!blacklistedExternalClassNames.contains(className)) {
                            listWithoutPlaceholders.add(className);
                        }
                    }
                    // Dedup and sort the resulting list.
                    final ArrayList<String> result = new ArrayList<>(listWithoutPlaceholders);
                    Collections.sort(result);
                    return result;
                }
            }
        };
    }

    /**
     * Create a LazyMap that maps from any value to the sorted, uniquified union of the keySets of the passed maps.
     */
    @SafeVarargs
    private final LazyMap<String, List<String>> lazyGetKeys(final LazyMap<String, DAGNode>... maps) {
        return sortedNonNullWithoutPlaceholders(new LazyMap<String, Set<String>>() {
            private Set<String> union = null;

            @Override
            protected Set<String> generateValue(final String ignored) {
                if (union != null) {
                    return union;
                } else {
                    @SuppressWarnings("unchecked")
                    final
                    Set<String>[] keySets = new Set[maps.length];
                    for (int i = 0; i < maps.length; i++) {
                        keySets[i] = maps[i].keySet();
                    }
                    union = Utils.union(keySets);
                    return union;
                }
            };
        });
    }

    /** Get the names of a collection of DAGNodes. (Result may contain duplicates, and is not sorted.) */
    @SafeVarargs
    private static List<String> getDAGNodeNames(final Collection<DAGNode>... nodeCollections) {
        int totSize = 0;
        for (final Collection<DAGNode> coll : nodeCollections) {
            totSize += coll.size();
        }
        final ArrayList<String> names = new ArrayList<>(totSize);
        for (final Collection<DAGNode> coll : nodeCollections) {
            for (final DAGNode node : coll) {
                names.add(node.name);
            }
        }
        return names;
    }

    /**
     * Lazily create a mapping from the name of a DAGNode to the DAGNode. The entire map is built the first time
     * get() is called.
     */
    private LazyMap<String, DAGNode> lazyGetDAGNodeNames(final List<? extends DAGNode> nodes) {
        return new LazyMap<String, DAGNode>() {
            @Override
            public void initialize() {
                for (final DAGNode node : nodes) {
                    map.put(node.name, node);
                }
            }
        };
    }

    /** Class that returns a list of Strings given a DAGNode */
    private static interface MapToConnectedClassNames {
        public Collection<String> getConnectedClassNames(DAGNode node);
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    private LazyMap<String, List<String>> lazyGetConnectedClassNames(final LazyMap<String, DAGNode> classNameToDAGNode,
            final MapToConnectedClassNames connectedClassNames) {
        return sortedNonNullWithoutPlaceholders(new LazyMap<String, Collection<String>>() {
            @Override
            protected Collection<String> generateValue(final String className) {
                final DAGNode classNode = classNameToDAGNode.get(className);
                if (classNode == null) {
                    return null;
                }
                return connectedClassNames.getConnectedClassNames(classNode);
            };
        });
    }

    // -------------------------------------------------------------------------------------------------------------
    // DAGs

    /** A map from class name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> classNameToStandardClassNode = lazyGetDAGNodeNames(standardClassNodes);

    /** A map from interface name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> interfaceNameToInterfaceNode = lazyGetDAGNodeNames(interfaceNodes);

    /** A map from annotation name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> annotationNameToAnnotationNode = lazyGetDAGNodeNames(annotationNodes);

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** The sorted unique names of all classes, interfaces and annotations found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllClasses = //
    lazyGetKeys(classNameToStandardClassNode, interfaceNameToInterfaceNode, annotationNameToAnnotationNode);

    /** The sorted unique names of all standard classes (non-interface, non-annotation) found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllStandardClasses = //
    lazyGetKeys(classNameToStandardClassNode);

    /** The sorted unique names of all interfaces found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllInterfaceClasses = //
    lazyGetKeys(interfaceNameToInterfaceNode);

    /** The sorted unique names of all annotation classes found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllAnnotationClasses = //
    lazyGetKeys(annotationNameToAnnotationNode);

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    public List<String> getNamesOfAllClasses() {
        return namesOfAllClasses.get("");
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    public List<String> getNamesOfAllStandardClasses() {
        return namesOfAllStandardClasses.get("");
    }

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return namesOfAllInterfaceClasses.get("");
    }

    /** Return the sorted unique names of all annotation classes found during the scan. */
    public List<String> getNamesOfAllAnnotationClasses() {
        return namesOfAllAnnotationClasses.get("");
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    private final LazyMap<String, List<String>> classNameToSubclassNames = //
    lazyGetConnectedClassNames(classNameToStandardClassNode, new MapToConnectedClassNames() {
        @Override
        public List<String> getConnectedClassNames(final DAGNode classNode) {
            return getDAGNodeNames(classNode.allSubNodes);
        }
    });

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        return classNameToSubclassNames.get(className);
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    private final LazyMap<String, List<String>> classNameToSuperclassNames = //
    lazyGetConnectedClassNames(classNameToStandardClassNode, new MapToConnectedClassNames() {
        @Override
        public List<String> getConnectedClassNames(final DAGNode classNode) {
            return getDAGNodeNames(classNode.allSuperNodes);
        }
    });

    /** Return the sorted list of names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        return classNameToSuperclassNames.get(className);
    }

    /**
     * A map from class name to the sorted list of unique names of types of fields in those classes, for fields
     * whose type is in a whitelisted (non-blacklisted) package.
     */
    private final LazyMap<String, List<String>> fieldTypeToClassNames = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final StandardClassDAGNode node : standardClassNodes) {
                for (final DAGNode fieldType : node.fieldTypeNodes) {
                    MultiSet.put(map, fieldType.name, node.name);
                }
            }
        }
    });

    /**
     * Return a sorted list of classes that have a field of the named type, where the field type is in a whitelisted
     * (non-blacklisted) package.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        return fieldTypeToClassNames.get(fieldTypeName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    private final LazyMap<String, List<String>> interfaceNameToSubinterfaceNames = //
    lazyGetConnectedClassNames(interfaceNameToInterfaceNode, new MapToConnectedClassNames() {
        @Override
        public List<String> getConnectedClassNames(final DAGNode interfaceNode) {
            return getDAGNodeNames(interfaceNode.allSubNodes);
        }
    });

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return interfaceNameToSubinterfaceNames.get(interfaceName);
    }

    /** Return the sorted list of names of all superinterfaces of the named interface. */
    private final LazyMap<String, List<String>> interfaceNameToSuperinterfaceNames = //
    lazyGetConnectedClassNames(interfaceNameToInterfaceNode, new MapToConnectedClassNames() {
        @Override
        public List<String> getConnectedClassNames(final DAGNode interfaceNode) {
            return getDAGNodeNames(interfaceNode.allSuperNodes);
        }
    });

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        return interfaceNameToSuperinterfaceNames.get(interfaceName);
    }

    /** Mapping from interface names to the sorted list of unique names of classes that implement the interface. */
    private final LazyMap<String, List<String>> interfaceNameToClassNames = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            // Create mapping from interface names to the names of classes that implement the interface.
            for (final StandardClassDAGNode classNode : standardClassNodes) {
                // For regular classes, cross-linked class names are the names of implemented interfaces.
                // Create reverse mapping from interfaces and superinterfaces implemented by the class
                // back to the class the interface implements
                for (final DAGNode interfaceNode : classNode.implementedInterfaceClassNodes) {
                    // Map from interface to implementing class
                    MultiSet.put(map, interfaceNode.name, classNode.name);
                    // Classes that subclass another class that implements an interface
                    // also implement the same interface.
                    for (final DAGNode subclassNode : classNode.allSubNodes) {
                        MultiSet.put(map, interfaceNode.name, subclassNode.name);
                    }

                    // Do the same for any superinterfaces of this interface: any class that
                    // implements an interface also implements all its superinterfaces, and so
                    // do all the subclasses of the class.
                    for (final DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                        MultiSet.put(map, superinterfaceNode.name, classNode.name);
                        for (final DAGNode subclassNode : classNode.allSubNodes) {
                            MultiSet.put(map, superinterfaceNode.name, subclassNode.name);
                        }
                    }
                }
            }
        }
    });

    /** Return the sorted list of names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        return interfaceNameToClassNames.get(interfaceName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** A MultiMap mapping from annotation name to the sorted unique list of names of the classes they annotate. */
    private final LazyMap<String, List<String>> annotationNameToAnnotatedClassNames = //
    lazyGetConnectedClassNames(annotationNameToAnnotationNode, new MapToConnectedClassNames() {
        @Override
        public Collection<String> getConnectedClassNames(final DAGNode annotationNode) {
            final ArrayList<DAGNode> annotatedClassNodes = new ArrayList<>(
                    ((AnnotationDAGNode) annotationNode).annotatedClassNodes);
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                annotatedClassNodes.addAll(((AnnotationDAGNode) subNode).annotatedClassNodes);
            }
            return getDAGNodeNames(annotatedClassNodes);
        }
    });

    /** Return the sorted list of names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return annotationNameToAnnotatedClassNames.get(annotationName);
    }

    /**
     * A map from the names of classes to the sorted list of names of annotations and meta-annotations on the
     * classes.
     */
    private final LazyMap<String, List<String>> classNameToAnnotationNames = //
    sortedNonNullWithoutPlaceholders(LazyMap.invertMultiSet(annotationNameToAnnotatedClassNames,
            annotationNameToAnnotationNode));

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return classNameToAnnotationNames.get(classOrInterfaceName);
    }

    /** A map from meta-annotation name to the set of names of the annotations they annotate. */
    private final LazyMap<String, List<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    lazyGetConnectedClassNames(annotationNameToAnnotationNode, new MapToConnectedClassNames() {
        @Override
        public Collection<String> getConnectedClassNames(final DAGNode annotationNode) {
            return getDAGNodeNames(annotationNode.allSubNodes);
        }
    });

    /**
     * Mapping from annotation name to the sorted list of names of annotations and meta-annotations on the
     * annotation.
     */
    private final LazyMap<String, List<String>> annotationNameToMetaAnnotationNames = //
    sortedNonNullWithoutPlaceholders(LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNames,
            annotationNameToAnnotationNode));

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return annotationNameToMetaAnnotationNames.get(annotationName);
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return metaAnnotationNameToAnnotatedAnnotationNames.get(metaAnnotationName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class graph visualization

    /**
     * Splits a .dot node label into two text lines, putting the package on one line and the class name on the next.
     */
    private static String label(final DAGNode node) {
        final String className = node.name;
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

        buf.append("\nnode[shape=box,style=filled,fillcolor=\"#fff2b6\"];\n");
        for (final DAGNode node : standardClassNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=diamond,style=filled,fillcolor=\"#b6e7ff\"];\n");
        for (final DAGNode node : interfaceNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=oval,style=filled,fillcolor=\"#f3c9ff\"];\n");
        for (final DAGNode node : annotationNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\n");
        for (final StandardClassDAGNode classNode : standardClassNodes) {
            for (final DAGNode superclassNode : classNode.directSuperNodes) {
                // class --> superclass
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(superclassNode) + "\"\n");
            }
            for (final DAGNode implementedInterfaceNode : classNode.implementedInterfaceClassNodes) {
                // class --<> implemented interface
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(implementedInterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
            for (final DAGNode fieldTypeNode : classNode.fieldTypeNodes) {
                // class --[] whitelisted field type
                buf.append("  \"" + label(fieldTypeNode) + "\" -> \"" + label(classNode)
                        + "\" [arrowtail=obox, dir=back]\n");
            }
        }
        for (final InterfaceDAGNode interfaceNode : interfaceNodes) {
            for (final DAGNode superinterfaceNode : interfaceNode.directSuperNodes) {
                // interface --> superinterface
                buf.append("  \"" + label(interfaceNode) + "\" -> \"" + label(superinterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
        }
        for (final AnnotationDAGNode annotationNode : annotationNodes) {
            for (final DAGNode metaAnnotationNode : annotationNode.directSuperNodes) {
                // annotation --o meta-annotation
                buf.append("  \"" + label(annotationNode) + "\" -> \"" + label(metaAnnotationNode)
                        + "\" [arrowhead=dot]\n");
            }
            for (final DAGNode annotatedClassNode : annotationNode.annotatedClassNodes) {
                // annotated class --o annotation
                buf.append("  \"" + label(annotatedClassNode) + "\" -> \"" + label(annotationNode)
                        + "\" [arrowhead=dot]\n");
            }
        }
        buf.append("}");
        return buf.toString();
    }
}
