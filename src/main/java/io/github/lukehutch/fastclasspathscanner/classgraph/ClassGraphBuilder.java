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
import io.github.lukehutch.fastclasspathscanner.utils.MultiMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

public class ClassGraphBuilder {
    private final ArrayList<ClassInfo> allClassInfo;

    public ClassGraphBuilder(final Collection<ClassInfo> relativePathToClassInfo) {
        this.allClassInfo = new ArrayList<>(relativePathToClassInfo);
    }

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

    /** Return names of all classes (including interfaces and annotations) reached during the scan. */
    public Set<String> getNamesOfAllClasses() {
        return classNameToClassNode.resolve().keySet();
    }

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

    /** Reverse mapping from interface names to the names of classes that implement the interface */
    private final LazyMap<String, HashSet<String>> interfaceNameToClassNames = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            // Create mapping from interface names to the names of classes that implement the interface.
            for (final DAGNode classNode : classNameToClassNode.resolve().values()) {
                // For regular classes, cross-linked class names are the names of implemented interfaces
                final ArrayList<String> interfaceNames = classNode.crossLinkedClassNames;
                if (interfaceNames != null) {
                    // Create reverse mapping from interfaces and superinterfaces implemented by the class
                    // back to the class the interface implements
                    for (final String interfaceName : interfaceNames) {
                        final DAGNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(interfaceName);
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

    // -------------------------------------------------------------------------------------------------------------

    /** A MultiSet mapping from annotation name to the names of the classes they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final DAGNode annotationNode : annotationNameToAnnotationNode.resolve().values()) {
                for (final DAGNode subNode : annotationNode.allSubNodes) {
                    MultiSet.putAll(map, annotationNode.name, subNode.crossLinkedClassNames);
                }
                MultiSet.putAll(map, annotationNode.name, annotationNode.crossLinkedClassNames);
            }
        }
    };

    /** A MultiMap mapping from annotation name to the names of the classes they annotate. */
    private final LazyMap<String, ArrayList<String>> annotationNameToAnnotatedClassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            for (final Entry<String, HashSet<String>> ent : annotationNameToAnnotatedClassNamesSet.resolve()
                    .entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    /** A map from meta-annotation name to the names of the annotations they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedAnnotationNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final DAGNode annotationNode : annotationNameToAnnotationNode.resolve().values()) {
                for (final DAGNode subNode : annotationNode.allSubNodes) {
                    MultiSet.put(map, annotationNode.name, subNode.name);
                }
            }
        }
    };

    /** A map from the names of classes to the names of annotations and meta-annotations on the classes. */
    private final LazyMap<String, ArrayList<String>> classNameToAnnotationNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            for (final Entry<String, HashSet<String>> ent : MultiSet.invert(
                    annotationNameToAnnotatedClassNamesSet.resolve()).entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /** Reverse mapping from meta-annotation names to the names of annotations that have the meta-annotation. */
    private final LazyMap<String, ArrayList<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            for (final Entry<String, HashSet<String>> ent : annotationNameToAnnotatedAnnotationNamesSet.resolve()
                    .entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    /** Mapping from annotation name to the names of annotations and meta-annotations on the annotation. */
    private final LazyMap<String, ArrayList<String>> annotationNameToMetaAnnotationNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            for (final Entry<String, HashSet<String>> ent : MultiSet.invert(
                    annotationNameToAnnotatedAnnotationNamesSet.resolve()).entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Return the names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        final DAGNode classNode = classNameToClassNode.resolve().get(className);
        if (classNode == null) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> subclasses = new ArrayList<>(classNode.allSubNodes.size());
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
            return subclasses;
        }
    }

    /** Return the names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        final DAGNode classNode = classNameToClassNode.resolve().get(className);
        if (classNode == null) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> superclasses = new ArrayList<>(classNode.allSuperNodes.size());
            for (final DAGNode subNode : classNode.allSuperNodes) {
                superclasses.add(subNode.name);
            }
            return superclasses;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final DAGNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(interfaceName);
        if (interfaceNode == null) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> subinterfaces = new ArrayList<>(interfaceNode.allSubNodes.size());
            for (final DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
            return subinterfaces;
        }
    }

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        final DAGNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(interfaceName);
        if (interfaceNode == null) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> superinterfaces = new ArrayList<>(interfaceNode.allSuperNodes.size());
            for (final DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
            return superinterfaces;
        }
    }

    /** Return the names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final HashSet<String> classes = interfaceNameToClassNames.resolve().get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        } else {
            return new ArrayList<>(classes);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Return the names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        final ArrayList<String> annotationNames = classNameToAnnotationNames.resolve().get(classOrInterfaceName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
    }

    /** Return the names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        final ArrayList<String> metaAnnotationNames = annotationNameToMetaAnnotationNames.resolve().get(
                annotationName);
        if (metaAnnotationNames == null) {
            return Collections.emptyList();
        } else {
            return metaAnnotationNames;
        }
    }

    /** Return the names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classNames = annotationNameToAnnotatedClassNames.resolve().get(annotationName);
        if (classNames == null) {
            return Collections.emptyList();
        } else {
            return classNames;
        }
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        final ArrayList<String> annotationNames = metaAnnotationNameToAnnotatedAnnotationNames.resolve().get(
                metaAnnotationName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
    }
}
