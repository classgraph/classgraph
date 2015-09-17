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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ClassGraphBuilder {
    private final ArrayList<ClassInfo> allClassInfo;

    public ClassGraphBuilder(final Collection<ClassInfo> relativePathToClassInfo) {
        this.allClassInfo = new ArrayList<>(relativePathToClassInfo);
    }

    // -------------------------------------------------------------------------------------------------------------
    // DAGs

    /** A map from class name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> classNameToClassNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : allClassInfo) {
                if (!classInfo.isAnnotation && !classInfo.isInterface) {
                    // Look up or create ClassNode object for this class
                    final DAGNode classNode = DAGNode.getOrNew(map, classInfo.className);
                    if (classInfo.interfaceNames != null) {
                        for (final String interfaceName : classInfo.interfaceNames) {
                            // Cross-link classes to the interfaces they implement
                            classNode.addCrossLink(interfaceName);
                        }
                    }
                    if (classInfo.superclassName != null) {
                        // Look up or create ClassNode object for superclass, and connect it to this class
                        DAGNode.getOrNew(map, classInfo.superclassName, classNode);
                    }
                }
            }
            DAGNode.findTransitiveClosure(map.values());
        }
    };

    /** A map from interface name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> interfaceNameToInterfaceNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : allClassInfo) {
                if (classInfo.isInterface) {
                    // Look up or create InterfaceNode for this interface
                    final DAGNode interfaceNode = DAGNode.getOrNew(map, classInfo.className);
                    if (classInfo.interfaceNames != null) {
                        // Look up or create InterfaceNode objects for superinterfaces,
                        // and connect them to this interface
                        for (final String superInterfaceName : classInfo.interfaceNames) {
                            DAGNode.getOrNew(map, superInterfaceName, interfaceNode);
                        }
                    }
                }
            }
            DAGNode.findTransitiveClosure(map.values());
        }
    };

    /** A map from annotation name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> annotationNameToAnnotationNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : allClassInfo) {
                if (classInfo.annotationNames != null) {
                    // Iterate through annotations on each scanned class
                    for (final String annotationName : classInfo.annotationNames) {
                        if (classInfo.isAnnotation) {
                            // If the annotated class is itself an annotation: look up or create AnnotationNode
                            // for the meta-annotation, and link it to a sub-node for the annotated annotation.
                            DAGNode.getOrNew(map, annotationName, DAGNode.getOrNew(map, classInfo.className));
                        } else {
                            // Link annotation to class
                            DAGNode.getOrNew(map, annotationName).addCrossLink(classInfo.className);
                        }
                    }
                }
            }
            DAGNode.findTransitiveClosure(map.values());
        }
    };

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Return names of all classes (including interfaces and annotations) reached during the scan. */
    public Set<String> getNamesOfAllClasses() {
        return classNameToClassNode.keySet();
    }

    /** Return the names of all subclasses of the named class. */
    private final LazyMap<String, ArrayList<String>> classNameToSubclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> subclasses = new ArrayList<>(classNode.allSubNodes.size());
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
            return subclasses;
        };
    };

    /** Return the names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        final ArrayList<String> subclassNames = classNameToSubclassNames.get(className);
        if (subclassNames == null) {
            return Collections.emptyList();
        } else {
            return subclassNames;
        }
    }

    /** Return the names of all superclasses of the named class. */
    private final LazyMap<String, ArrayList<String>> classNameToSuperclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> superclasses = new ArrayList<>(classNode.allSuperNodes.size());
            for (final DAGNode superNode : classNode.allSuperNodes) {
                superclasses.add(superNode.name);
            }
            return superclasses;
        };
    };

    /** Return the names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        final ArrayList<String> superclassNames = classNameToSuperclassNames.get(className);
        if (superclassNames == null) {
            return Collections.emptyList();
        } else {
            return superclassNames;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the names of all subinterfaces of the named interface. */
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
            return subinterfaces;
        };
    };

    /** Return the names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final ArrayList<String> subinterfaceNames = interfaceNameToSubinterfaceNames.get(interfaceName);
        if (subinterfaceNames == null) {
            return Collections.emptyList();
        } else {
            return subinterfaceNames;
        }
    }

    /** Return the names of all superinterfaces of the named interface. */
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
            for (final DAGNode classNode : classNameToClassNode.values()) {
                // For regular classes, cross-linked class names are the names of implemented interfaces
                final ArrayList<String> interfaceNames = classNode.crossLinkedClassNames;
                if (interfaceNames != null) {
                    // Create reverse mapping from interfaces and superinterfaces implemented by the class
                    // back to the class the interface implements
                    for (final String interfaceName : interfaceNames) {
                        final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
                        if (interfaceNode != null) {
                            // Map from interface to implementing class
                            MultiSet.put(map, interfaceName, classNode.name);
                            // Classes that subclass another class that implements an interface
                            // also implement the same interface.
                            for (final DAGNode subclassNode : classNode.allSubNodes) {
                                MultiSet.put(map, interfaceName, subclassNode.name);
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
            }
        }
    };

    /** Mapping from interface names to the list of unique names of classes that implement the interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToClassNames = //
    LazyMap.convertToMultiMap(interfaceNameToClassNamesSet);

    /** Return the names of all classes implementing the named interface. */
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
            final HashSet<String> classNames = new HashSet<>();
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                classNames.addAll(subNode.crossLinkedClassNames);
            }
            classNames.addAll(annotationNode.crossLinkedClassNames);
            return classNames;
        };
    };

    /** A MultiMap mapping from annotation name to the uniquified list of names of the classes they annotate. */
    private final LazyMap<String, ArrayList<String>> annotationNameToAnnotatedClassNames = LazyMap
            .convertToMultiMap(annotationNameToAnnotatedClassNamesSet);

    /** Return the names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classNames = annotationNameToAnnotatedClassNames.get(annotationName);
        if (classNames == null) {
            return Collections.emptyList();
        } else {
            return classNames;
        }
    }

    /** A map from the names of classes to the names of annotations and meta-annotations on the classes. */
    private final LazyMap<String, ArrayList<String>> classNameToAnnotationNames = //
    LazyMap.convertToMultiMap( //
    LazyMap.invertMultiSet(annotationNameToAnnotatedClassNamesSet, annotationNameToAnnotationNode));

    /** Return the names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        final ArrayList<String> annotationNames = classNameToAnnotationNames.get(classOrInterfaceName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
    }

    /** A map from meta-annotation name to the names of the annotations they annotate. */
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

    /** Mapping from annotation name to the names of annotations and meta-annotations on the annotation. */
    private final LazyMap<String, ArrayList<String>> annotationNameToMetaAnnotationNames = //
    LazyMap.convertToMultiMap( //
    LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNamesSet, annotationNameToAnnotationNode));

    /** Return the names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        final ArrayList<String> metaAnnotationNames = annotationNameToMetaAnnotationNames.get(annotationName);
        if (metaAnnotationNames == null) {
            return Collections.emptyList();
        } else {
            return metaAnnotationNames;
        }
    }

    /** Mapping from meta-annotation names to the names of annotations that have the meta-annotation. */
    private final LazyMap<String, ArrayList<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    LazyMap.convertToMultiMap(metaAnnotationNameToAnnotatedAnnotationNamesSet);

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
}
