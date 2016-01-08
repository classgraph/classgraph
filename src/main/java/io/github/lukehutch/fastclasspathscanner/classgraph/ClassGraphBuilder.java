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
    private final HashSet<String> nonWhitelistedPlaceholderClassNames = new HashSet<>();

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
        final ArrayList<String> whitelistedClasses = new ArrayList<>();
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
        }
        externalSuperclasses.removeAll(whitelistedClasses);
        externalInterfaces.removeAll(whitelistedClasses);
        externalAnnotations.removeAll(whitelistedClasses);
        // If an external annotation is also extended as an interface, just display it as an interface
        // in graph visualizations
        externalAnnotations.removeAll(externalInterfaces);

        // Create placeholder nodes
        for (final String externalSuperclassName : externalSuperclasses) {
            final StandardClassDAGNode newNode = new StandardClassDAGNode(externalSuperclassName);
            classNameToDAGNode.put(externalSuperclassName, newNode);
            standardClassNodes.add(newNode);
            if (!scanSpec.classIsWhitelisted(externalSuperclassName)) {
                nonWhitelistedPlaceholderClassNames.add(externalSuperclassName);
            }
        }
        for (final String externalInterfaceName : externalInterfaces) {
            final InterfaceDAGNode newNode = new InterfaceDAGNode(externalInterfaceName);
            classNameToDAGNode.put(externalInterfaceName, newNode);
            interfaceNodes.add(newNode);
            if (!scanSpec.classIsWhitelisted(externalInterfaceName)) {
                nonWhitelistedPlaceholderClassNames.add(externalInterfaceName);
            }
        }
        for (final String externalAnnotationName : externalAnnotations) {
            final AnnotationDAGNode newNode = new AnnotationDAGNode(externalAnnotationName);
            classNameToDAGNode.put(externalAnnotationName, newNode);
            annotationNodes.add(newNode);
            if (!scanSpec.classIsWhitelisted(externalAnnotationName)) {
                nonWhitelistedPlaceholderClassNames.add(externalAnnotationName);
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

    /**
     * Wrap a lazy multimap so that when a key is looked up, if there is no correspoding value, the empty list is
     * returned. Otherwise, filter the list of values associated with the key to remove the names of placeholder
     * DAGNodes (indicating a class was not itself in a whitelisted package, but was referenced by a class in a
     * whitelisted package). Sort and return the filtered list.
     */
    private LazyMap<String, List<String>> sortedNonNullWithoutPlaceholders( //
            final LazyMap<String, ? extends Collection<String>> underlyingMap) {
        return new LazyMap<String, List<String>>() {
            @Override
            public List<String> get(final String key) {
                final Collection<String> classNameList = underlyingMap.get(key);
                if (classNameList == null) {
                    // Return empty list if the map contains no value for a given key.
                    return Collections.emptyList();
                } else {
                    // Filter out placeholder nodes -- need to look up each class in the list by name to see
                    // if it was a placeholder.
                    final List<String> listWithoutPlaceholders = new ArrayList<>();
                    for (final String className : classNameList) {
                        // Strip out placeholder nodes, unless the class was whitelisted by name
                        if (!nonWhitelistedPlaceholderClassNames.contains(className)) {
                            listWithoutPlaceholders.add(className);
                        }
                    }
                    // Sort the resulting list.
                    Collections.sort(listWithoutPlaceholders);
                    return listWithoutPlaceholders;
                }
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------
    // DAGs

    /** A map from class name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> classNameToStandardClassNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode classNode : standardClassNodes) {
                map.put(classNode.name, classNode);
            }
        }
    };

    /** A map from interface name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> interfaceNameToInterfaceNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode interfaceNode : interfaceNodes) {
                map.put(interfaceNode.name, interfaceNode);
            }
        }
    };

    /** A map from annotation name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> annotationNameToAnnotationNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode annotationNode : annotationNodes) {
                map.put(annotationNode.name, annotationNode);
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** The sorted unique names of all classes, interfaces and annotations found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllClasses = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, HashSet<String>>() {
        @Override
        protected HashSet<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.union(classNameToStandardClassNode.keySet(), interfaceNameToInterfaceNode.keySet(),
                    annotationNameToAnnotationNode.keySet());
        };
    });

    /**
     * The sorted unique names of all standard classes (non-interface, non-annotation classes) found during the
     * scan.
     */
    private final LazyMap<String, List<String>> namesOfAllStandardClasses = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, Set<String>>() {
        @Override
        protected Set<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return classNameToStandardClassNode.keySet();
        };
    });

    /** The sorted unique names of all interfaces found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllInterfaceClasses = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, Set<String>>() {
        @Override
        protected Set<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return interfaceNameToInterfaceNode.keySet();
        };
    });

    /** The sorted unique names of all annotation classes found during the scan. */
    private final LazyMap<String, List<String>> namesOfAllAnnotationClasses = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, Set<String>>() {
        @Override
        protected Set<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return annotationNameToAnnotationNode.keySet();
        };
    });

    /**
     * Return the sorted unique names of all classes, interfaces and annotations found during the scan.
     */
    public List<String> getNamesOfAllClasses() {
        return namesOfAllClasses.get("");
    }

    /**
     * Return the sorted unique names of all standard classes (non-interface, non-annotation classes) found during
     * the scan.
     */
    public List<String> getNamesOfAllStandardClasses() {
        return namesOfAllStandardClasses.get("");
    }

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return namesOfAllInterfaceClasses.get("");
    }

    /**
     * Return the sorted unique names of all annotation classes found during the scan.
     */
    public List<String> getNamesOfAllAnnotationClasses() {
        return namesOfAllAnnotationClasses.get("");
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    private final LazyMap<String, List<String>> classNameToSubclassNames = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, List<String>>() {
        @Override
        protected List<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final List<String> subclasses = new ArrayList<>(classNode.allSubNodes.size());
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
            return subclasses;
        };
    });

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        return classNameToSubclassNames.get(className);
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    private final LazyMap<String, List<String>> classNameToSuperclassNames = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, List<String>>() {
        @Override
        protected List<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final List<String> superclasses = new ArrayList<>(classNode.allSuperNodes.size());
            for (final DAGNode superNode : classNode.allSuperNodes) {
                superclasses.add(superNode.name);
            }
            return superclasses;
        };
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
    sortedNonNullWithoutPlaceholders( //
    LazyMap.invertMultiSet( //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final StandardClassDAGNode node : standardClassNodes) {
                if (!node.whitelistedFieldTypeNodes.isEmpty()) {
                    final HashSet<String> fieldTypeNames = new HashSet<>();
                    for (final DAGNode fieldType : node.whitelistedFieldTypeNodes) {
                        fieldTypeNames.add(fieldType.name);
                    }
                    map.put(node.name, fieldTypeNames);
                }
            }
        };
    }));

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
    sortedNonNullWithoutPlaceholders(new LazyMap<String, List<String>>() {
        @Override
        protected List<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final List<String> subinterfaces = new ArrayList<>(interfaceNode.allSubNodes.size());
            for (final DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
            return subinterfaces;
        };
    });

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return interfaceNameToSubinterfaceNames.get(interfaceName);
    }

    /** Return the sorted list of names of all superinterfaces of the named interface. */
    private final LazyMap<String, List<String>> interfaceNameToSuperinterfaceNames = //
    sortedNonNullWithoutPlaceholders(new LazyMap<String, List<String>>() {
        @Override
        protected List<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final List<String> superinterfaces = new ArrayList<>(interfaceNode.allSuperNodes.size());
            for (final DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
            return superinterfaces;
        };
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
                final ArrayList<ImplementedInterfaceDAGNode> interfaceNodes = //
                classNode.implementedInterfaceClassNodes;
                for (final DAGNode interfaceNode : interfaceNodes) {
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

    /** A MultiSet mapping from annotation name to the set of names of the classes they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        protected HashSet<String> generateValue(final String annotationName) {
            final DAGNode annotationNode = annotationNameToAnnotationNode.get(annotationName);
            if (annotationNode == null) {
                return null;
            }
            // Get the names of all classes annotated by this annotation, or by any sub-annotation
            // (i.e. by any annotation meta-annotated by this one).
            final HashSet<String> classNames = new HashSet<>();
            for (final DAGNode crossLinkedNode : ((AnnotationDAGNode) annotationNode).annotatedClassNodes) {
                classNames.add(crossLinkedNode.name);
            }
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                for (final DAGNode crossLinkedNode : ((AnnotationDAGNode) subNode).annotatedClassNodes) {
                    classNames.add(crossLinkedNode.name);
                }
            }
            return classNames;
        };
    };

    /** A MultiMap mapping from annotation name to the sorted list of names of the classes they annotate. */
    private final LazyMap<String, List<String>> annotationNameToAnnotatedClassNames = //
    sortedNonNullWithoutPlaceholders(annotationNameToAnnotatedClassNamesSet);

    /** Return the sorted list of names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return annotationNameToAnnotatedClassNames.get(annotationName);
    }

    /**
     * A map from the names of classes to the sorted list of names of annotations and meta-annotations on the
     * classes.
     */
    private final LazyMap<String, List<String>> classNameToAnnotationNames = //
    sortedNonNullWithoutPlaceholders(LazyMap.invertMultiSet(annotationNameToAnnotatedClassNamesSet,
            annotationNameToAnnotationNode));

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return classNameToAnnotationNames.get(classOrInterfaceName);
    }

    /** A map from meta-annotation name to the set of names of the annotations they annotate. */
    private final LazyMap<String, HashSet<String>> metaAnnotationNameToAnnotatedAnnotationNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        protected HashSet<String> generateValue(final String annotationName) {
            final DAGNode annotationNode = annotationNameToAnnotationNode.get(annotationName);
            if (annotationNode == null) {
                return null;
            }
            final HashSet<String> subNodes = new HashSet<>();
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                subNodes.add(subNode.name);
            }
            return subNodes;
        }
    };

    /**
     * Mapping from annotation name to the sorted list of names of annotations and meta-annotations on the
     * annotation.
     */
    private final LazyMap<String, List<String>> annotationNameToMetaAnnotationNames = //
    sortedNonNullWithoutPlaceholders(LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNamesSet,
            annotationNameToAnnotationNode));

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return annotationNameToMetaAnnotationNames.get(annotationName);
    }

    /**
     * Mapping from meta-annotation names to the sorted list of names of annotations that have the meta-annotation.
     */
    private final LazyMap<String, List<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    sortedNonNullWithoutPlaceholders(metaAnnotationNameToAnnotatedAnnotationNamesSet);

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
            for (final DAGNode fieldTypeNode : classNode.whitelistedFieldTypeNodes) {
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
