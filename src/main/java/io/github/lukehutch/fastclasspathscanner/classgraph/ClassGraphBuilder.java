/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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

import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class ClassGraphBuilder {
    ArrayList<StandardClassDAGNode> standardClassNodes = new ArrayList<>();
    ArrayList<InterfaceDAGNode> interfaceNodes = new ArrayList<>();
    ArrayList<AnnotationDAGNode> annotationNodes = new ArrayList<>();

    public ClassGraphBuilder(final Collection<ClassInfo> classInfoFromScan) {
        // Take care of Scala quirks
        final ArrayList<ClassInfo> allClassInfo = new ArrayList<>(Utils.mergeScalaAuxClasses(classInfoFromScan));

        // Create DAG node for each class
        final HashMap<String, DAGNode> classNameToDAGNode = new HashMap<>();
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
    private final LazyMap<String, ArrayList<String>> namesOfAllClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(classNameToStandardClassNode.keySet(), interfaceNameToInterfaceNode.keySet(),
                    annotationNameToAnnotationNode.keySet());
        };
    };

    /**
     * The sorted unique names of all standard classes (non-interface, non-annotation classes) found during the
     * scan.
     */
    private final LazyMap<String, ArrayList<String>> namesOfAllStandardClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(classNameToStandardClassNode.keySet());
        };
    };

    /** The sorted unique names of all interfaces found during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllInterfaceClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(interfaceNameToInterfaceNode.keySet());
        };
    };

    /** The sorted unique names of all annotation classes found during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllAnnotationClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(annotationNameToAnnotationNode.keySet());
        };
    };

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
    private final LazyMap<String, ArrayList<String>> classNameToSubclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> subclasses = new ArrayList<>(classNode.allSubNodes.size());
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
            Collections.sort(subclasses);
            return subclasses;
        };
    };

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        final ArrayList<String> subclassNames = classNameToSubclassNames.get(className);
        if (subclassNames == null) {
            return Collections.emptyList();
        } else {
            return subclassNames;
        }
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    private final LazyMap<String, ArrayList<String>> classNameToSuperclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> superclasses = new ArrayList<>(classNode.allSuperNodes.size());
            for (final DAGNode superNode : classNode.allSuperNodes) {
                superclasses.add(superNode.name);
            }
            Collections.sort(superclasses);
            return superclasses;
        };
    };

    /** Return the sorted list of names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        final ArrayList<String> superclassNames = classNameToSuperclassNames.get(className);
        if (superclassNames == null) {
            return Collections.emptyList();
        } else {
            return superclassNames;
        }
    }

    /**
     * A map from class name to the sorted list of unique names of types of fields in those classes, for fields
     * whose type is in a whitelisted (non-blacklisted) package.
     */
    private final LazyMap<String, ArrayList<String>> fieldTypeToClassNames = //
    LazyMap.convertToMultiMapSorted( //
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
        final List<String> classesWithFieldOfNamedType = fieldTypeToClassNames.get(fieldTypeName);
        if (classesWithFieldOfNamedType == null) {
            return Collections.emptyList();
        } else {
            return classesWithFieldOfNamedType;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToSubinterfaceNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final ArrayList<String> subinterfaces = new ArrayList<>(interfaceNode.allSubNodes.size());
            for (final DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
            Collections.sort(subinterfaces);
            return subinterfaces;
        };
    };

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final ArrayList<String> subinterfaceNames = interfaceNameToSubinterfaceNames.get(interfaceName);
        if (subinterfaceNames == null) {
            return Collections.emptyList();
        } else {
            return subinterfaceNames;
        }
    }

    /** Return the sorted list of names of all superinterfaces of the named interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToSuperinterfaceNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final ArrayList<String> superinterfaces = new ArrayList<>(interfaceNode.allSuperNodes.size());
            for (final DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
            Collections.sort(superinterfaces);
            return superinterfaces;
        };
    };

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        final ArrayList<String> superinterfaceNames = interfaceNameToSuperinterfaceNames.get(interfaceName);
        if (superinterfaceNames == null) {
            return Collections.emptyList();
        } else {
            return superinterfaceNames;
        }
    }

    /** Mapping from interface names to the set of names of classes that implement the interface. */
    private final LazyMap<String, HashSet<String>> interfaceNameToClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            // Create mapping from interface names to the names of classes that implement the interface.
            for (final StandardClassDAGNode classNode : standardClassNodes) {
                // For regular classes, cross-linked class names are the names of implemented interfaces.
                // Create reverse mapping from interfaces and superinterfaces implemented by the class
                // back to the class the interface implements
                final ArrayList<InterfaceDAGNode> interfaceNodes = classNode.implementedInterfaceClassNodes;
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
    };

    /** Mapping from interface names to the sorted list of unique names of classes that implement the interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToClassNames = //
    LazyMap.convertToMultiMapSorted(interfaceNameToClassNamesSet);

    /** Return the sorted list of names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final ArrayList<String> classes = interfaceNameToClassNames.get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        } else {
            return classes;
        }
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
    private final LazyMap<String, ArrayList<String>> annotationNameToAnnotatedClassNames = LazyMap
            .convertToMultiMapSorted(annotationNameToAnnotatedClassNamesSet);

    /** Return the sorted list of names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classNames = annotationNameToAnnotatedClassNames.get(annotationName);
        if (classNames == null) {
            return Collections.emptyList();
        } else {
            return classNames;
        }
    }

    /**
     * A map from the names of classes to the sorted list of names of annotations and meta-annotations on the
     * classes.
     */
    private final LazyMap<String, ArrayList<String>> classNameToAnnotationNames = //
    LazyMap.convertToMultiMapSorted( //
    LazyMap.invertMultiSet(annotationNameToAnnotatedClassNamesSet, annotationNameToAnnotationNode));

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        final ArrayList<String> annotationNames = classNameToAnnotationNames.get(classOrInterfaceName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
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
    private final LazyMap<String, ArrayList<String>> annotationNameToMetaAnnotationNames = //
    LazyMap.convertToMultiMapSorted( //
    LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNamesSet, annotationNameToAnnotationNode));

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        final ArrayList<String> metaAnnotationNames = annotationNameToMetaAnnotationNames.get(annotationName);
        if (metaAnnotationNames == null) {
            return Collections.emptyList();
        } else {
            return metaAnnotationNames;
        }
    }

    /**
     * Mapping from meta-annotation names to the sorted list of names of annotations that have the meta-annotation.
     */
    private final LazyMap<String, ArrayList<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    LazyMap.convertToMultiMapSorted(metaAnnotationNameToAnnotatedAnnotationNamesSet);

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        final ArrayList<String> annotationNames = metaAnnotationNameToAnnotatedAnnotationNames
                .get(metaAnnotationName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
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
                // class --| whitelisted field type
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(fieldTypeNode) + "\" [arrowhead=obox]\n");
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
