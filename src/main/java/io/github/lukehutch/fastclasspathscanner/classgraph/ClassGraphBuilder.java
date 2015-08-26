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

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClassGraphBuilder {
    /**
     * A map from the relative path of classes encountered so far during a scan to the information extracted from
     * the class. If the same relative file path is encountered more than once, the second and subsequent instances
     * are ignored, because they are masked by the earlier occurrence of the class in the classpath. (This is a
     * ConcurrentHashMap so that classpath scanning can be parallelized.)
     */
    private final ConcurrentHashMap<String, ClassInfo> relativePathToClassInfo = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the upwards and downwards transitive closure for each node in a graph. Assumes the graph is a DAG in
     * general, but handles cycles (which may occur in the case of meta-annotations).
     */
    private static void findTransitiveClosure(final Collection<? extends DAGNode> nodes) {
        // Find top nodes as initial active set
        HashSet<DAGNode> activeTopDownNodes = new HashSet<>();
        for (final DAGNode node : nodes) {
            if (node.directSuperNodes.isEmpty()) {
                activeTopDownNodes.addAll(node.directSubNodes);
            }
        }
        // Use DP-style "wavefront" to find top-down transitive closure, even if there are cycles
        while (!activeTopDownNodes.isEmpty()) {
            final HashSet<DAGNode> activeTopDownNodesNext = new HashSet<>(activeTopDownNodes.size());
            for (final DAGNode node : activeTopDownNodes) {
                boolean changed = node.allSuperNodes.addAll(node.directSuperNodes);
                for (final DAGNode superNode : node.directSuperNodes) {
                    changed |= node.allSuperNodes.addAll(superNode.allSuperNodes);
                }
                if (changed) {
                    for (final DAGNode subNode : node.directSubNodes) {
                        activeTopDownNodesNext.add(subNode);
                    }
                }
            }
            activeTopDownNodes = activeTopDownNodesNext;
        }

        // Find bottom nodes as initial active set
        HashSet<DAGNode> activeBottomUpNodes = new HashSet<>();
        for (final DAGNode node : nodes) {
            if (node.directSubNodes.isEmpty()) {
                activeBottomUpNodes.addAll(node.directSuperNodes);
            }
        }
        // Use DP-style "wavefront" to find bottom-up transitive closure, even if there are cycles
        while (!activeBottomUpNodes.isEmpty()) {
            final HashSet<DAGNode> activeBottomUpNodesNext = new HashSet<>(activeBottomUpNodes.size());
            for (final DAGNode node : activeBottomUpNodes) {
                boolean changed = node.allSubNodes.addAll(node.directSubNodes);
                for (final DAGNode subNode : node.directSubNodes) {
                    changed |= node.allSubNodes.addAll(subNode.allSubNodes);
                }
                if (changed) {
                    for (final DAGNode superNode : node.directSuperNodes) {
                        activeBottomUpNodesNext.add(superNode);
                    }
                }
            }
            activeBottomUpNodes = activeBottomUpNodesNext;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A map from class name to the corresponding ClassNode object. */
    private final LazyMap<String, ClassNode> classNameToClassNode = //
    new LazyMap<String, ClassNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : relativePathToClassInfo.values()) {
                if (!classInfo.isAnnotation && !classInfo.isInterface) {
                    // Look up or create ClassNode object for this class
                    ClassNode classNode = map.get(classInfo.className);
                    if (classNode == null) {
                        map.put(classInfo.className, classNode = new ClassNode(classInfo.className,
                                classInfo.interfaceNames));
                    } else {
                        classNode.addInterfaces(classInfo.interfaceNames);
                    }
                    if (classInfo.superclassName != null) {
                        // Look up or create ClassNode object for superclass, and connect it to this class
                        ClassNode superclassNode = map.get(classInfo.superclassName);
                        if (superclassNode == null) {
                            // The superclass of this class has not yet been encountered on the classpath
                            map.put(classInfo.superclassName, superclassNode = new ClassNode(
                                    classInfo.superclassName, classNode));
                        } else {
                            superclassNode.addSubNode(classNode);
                        }
                    }

                }
            }
            findTransitiveClosure(map.values());
        }
    };

    /** A map from class name to the corresponding InterfaceNode object. */
    private final LazyMap<String, InterfaceNode> interfaceNameToInterfaceNode = //
    new LazyMap<String, InterfaceNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : relativePathToClassInfo.values()) {
                if (classInfo.isInterface) {
                    // Look up or create InterfaceNode for this interface
                    InterfaceNode interfaceNode = map.get(classInfo.className);
                    if (interfaceNode == null) {
                        map.put(classInfo.className, interfaceNode = new InterfaceNode(classInfo.className));
                    }
                    if (classInfo.interfaceNames != null) {
                        // Look up or create InterfaceNode objects for superinterfaces, and connect them
                        // to this interface
                        for (final String superInterfaceName : classInfo.interfaceNames) {
                            InterfaceNode superInterfaceNode = map.get(superInterfaceName);
                            if (superInterfaceNode == null) {
                                map.put(superInterfaceName, superInterfaceNode = new InterfaceNode(
                                        superInterfaceName, interfaceNode));
                            } else {
                                superInterfaceNode.addSubNode(interfaceNode);
                            }
                        }
                    }
                }
            }
            findTransitiveClosure(map.values());
        }
    };

    /** A map from class name to the corresponding AnnotationNode object. */
    private final LazyMap<String, AnnotationNode> annotationNameToAnnotationNode = //
    new LazyMap<String, AnnotationNode>() {
        @Override
        public void initialize() {
            for (final ClassInfo classInfo : relativePathToClassInfo.values()) {
                if (classInfo.annotationNames != null) {
                    for (final String annotationName : classInfo.annotationNames) {
                        // Look up or create AnnotationNode for each annotation on class
                        AnnotationNode annotationNode = map.get(annotationName);
                        if (annotationNode == null) {
                            map.put(annotationName, annotationNode = new AnnotationNode(annotationName));
                        }
                        if (classInfo.isAnnotation) {
                            // If the annotated class is itself an annotation
                            // Look up or create AnnotationNode for the annotated class
                            AnnotationNode annotatedAnnotationNode = map.get(classInfo.className);
                            if (annotatedAnnotationNode == null) {
                                map.put(classInfo.className, annotatedAnnotationNode = new AnnotationNode(
                                        classInfo.className));
                            }
                            // Link meta-annotation to annotation
                            annotationNode.addSubNode(annotatedAnnotationNode);
                            annotationNode.addAnnotatedAnnotation(classInfo.className);
                        } else {
                            // Link annotation to class
                            annotationNode.addAnnotatedClass(classInfo.className);
                        }
                    }
                }
            }
            findTransitiveClosure(map.values());
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    private Collection<ClassNode> allClassNodes() {
        return classNameToClassNode.resolve().values();
    }

    /** Return names of all classes (including interfaces and annotations) reached during the scan. */
    public Set<String> getNamesOfAllClasses() {
        return classNameToClassNode.resolve().keySet();
    }

    private Collection<InterfaceNode> allInterfaceNodes() {
        return interfaceNameToInterfaceNode.resolve().values();
    }

    private Collection<AnnotationNode> allAnnotationNodes() {
        return annotationNameToAnnotationNode.resolve().values();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Reverse mapping from interface names to the names of classes that implement the interface */
    private final LazyMap<String, ArrayList<String>> interfaceNameToClassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            // Perform topological sort on class tree
            final ArrayList<ClassNode> classNodeTopoOrder = DAGNode.topoSort(allClassNodes());

            // Perform topological sort on interface DAG
            final ArrayList<InterfaceNode> interfaceNodeTopoOrder = DAGNode.topoSort(allInterfaceNodes());

            // Reverse mapping from interface to classes that implement the interface.
            final HashMap<String, HashSet<DAGNode>> interfaceNameToClassNodesSet = new HashMap<>();

            // Create mapping from interface names to the names of classes that implement the interface.
            for (final DAGNode classNode : classNodeTopoOrder) {
                final ArrayList<String> interfaceNames = ((ClassNode) classNode).interfaceNames;
                if (interfaceNames != null) {
                    // Map from interface back to classes that implement the interface
                    final HashSet<String> interfacesAndSuperinterfacesUnion = new HashSet<>();
                    for (final String interfaceName : interfaceNames) {
                        // Any class that implements an interface also implements all its superinterfaces
                        interfacesAndSuperinterfacesUnion.add(interfaceName);
                        final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(
                                interfaceName);
                        if (interfaceNode != null) {
                            for (final DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                                interfacesAndSuperinterfacesUnion.add(superinterfaceNode.name);
                            }
                        }
                    }
                    for (final String interfaceName : interfacesAndSuperinterfacesUnion) {
                        // Add mapping from interface to implementing classes
                        MultiSet.put(interfaceNameToClassNodesSet, interfaceName, classNode);
                    }
                }
            }

            // Classes that subclass another class that implements an interface also implement the same interface.
            // Add these to the mapping from interface back to the classes that implement the interface.
            for (final DAGNode interfaceNode : interfaceNodeTopoOrder) {
                // Get all classes that implement this interface
                final HashSet<DAGNode> implementingClasses = interfaceNameToClassNodesSet.get( //
                        interfaceNode.name);
                if (implementingClasses != null) {
                    // Get the union of all subclasses of all classes that implement this interface
                    final HashSet<DAGNode> subClassUnion = new HashSet<DAGNode>();
                    for (final DAGNode implementingClass : implementingClasses) {
                        subClassUnion.addAll(implementingClass.allSubNodes);
                    }
                    // Add to the mapping from the interface to each subclass of the class that implements
                    // the interface.
                    implementingClasses.addAll(subClassUnion);
                }
            }
            // Convert interface mapping to String->String
            for (final Entry<String, HashSet<DAGNode>> ent : interfaceNameToClassNodesSet.entrySet()) {
                final HashSet<DAGNode> nodes = ent.getValue();
                final ArrayList<String> classNameList = new ArrayList<>(nodes.size());
                for (final DAGNode classNode : nodes) {
                    classNameList.add(classNode.name);
                }
                map.put(ent.getKey(), classNameList);
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /** A map from annotation name to the names of the classes they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final AnnotationNode annotationNode : allAnnotationNodes()) {
                for (final DAGNode subNode : annotationNode.allSubNodes) {
                    MultiSet.putAll(map, annotationNode.name, ((AnnotationNode) subNode).annotatedClassNames);
                }
                MultiSet.putAll(map, annotationNode.name, annotationNode.annotatedClassNames);
            }
        }
    };

    /** A map from meta-annotation name to the names of the annotations they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedAnnotationNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            for (final AnnotationNode annotationNode : allAnnotationNodes()) {
                for (final DAGNode subNode : annotationNode.allSubNodes) {
                    MultiSet.put(map, annotationNode.name, subNode.name);
                }
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /** Reverse mapping from annotation/meta-annotation names to the names of classes that have the annotation. */
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

    /** Mapping from class name to the names of annotations and meta-annotations on the class. */
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
        final ClassNode classNode = classNameToClassNode.resolve().get(className);
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
        final ClassNode classNode = classNameToClassNode.resolve().get(className);
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
        final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(interfaceName);
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
        final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.resolve().get(interfaceName);
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
        final ArrayList<String> classes = interfaceNameToClassNames.resolve().get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        } else {
            return classes;
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

    // -------------------------------------------------------------------------------------------------------------

    /** Clear all the data structures to be ready for another scan. */
    public void reset() {
        relativePathToClassInfo.clear();

        classNameToClassNode.clear();
        interfaceNameToInterfaceNode.clear();
        annotationNameToAnnotationNode.clear();

        annotationNameToAnnotatedClassNamesSet.clear();
        annotationNameToAnnotatedAnnotationNamesSet.clear();

        annotationNameToAnnotatedClassNames.clear();
        classNameToAnnotationNames.clear();
        metaAnnotationNameToAnnotatedAnnotationNames.clear();
        annotationNameToMetaAnnotationNames.clear();
        interfaceNameToClassNames.clear();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Try creating a new ClassInfo object. Returns null if the file at the relative path has already been seen by
     * the classpath scanner with a smaller classpath index, indicating that the new file was masked by an earlier
     * definition of the class.
     */
    private ClassInfo newClassInfo(final String relativePath, final int classpathEltIdx) {
        ClassInfo newClassInfo = new ClassInfo(relativePath, classpathEltIdx);
        ClassInfo oldClassInfo = relativePathToClassInfo.put(relativePath, newClassInfo);
        if (oldClassInfo == null || oldClassInfo.classpathElementIndex > newClassInfo.classpathElementIndex) {
            // This is the first time we have encountered this class, or we have encountered it before but at
            // a larger classpath index (i.e. the definition we just found occurs earlier in the classpath, so
            // the new class masks the version we already found -- this can occur with parallel scanning).
            return newClassInfo;
        } else if (oldClassInfo.classpathElementIndex == newClassInfo.classpathElementIndex) {
            // Two files with the same name occurred within the same classpath element.
            // Should never happen (paths are unique on filesystems and in zipfiles), but for safety, just
            // arbitrarily reject one of them here.
            return null;
        }
        // The new class was masked by a class with the same name earlier in the classpath. Need to put the
        // old ClassInfo back into the map, but need to make sure that the final ClassInfo that ends up in
        // the map is the one with the lowest index, to handle race conditions.
        do {
            newClassInfo = oldClassInfo;
            oldClassInfo = relativePathToClassInfo.put(relativePath, oldClassInfo);
        } while (oldClassInfo.classpathElementIndex < newClassInfo.classpathElementIndex);
        // New class was masked by an earlier definition -- return null
        return null;
    }

    /**
     * Directly examine contents of classfile binary header.
     */
    public void readClassInfoFromClassfileHeader(final String relativePath, final InputStream inputStream,
            final int classpathEltIdx, final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> // 
            classNameToStaticFieldnameToMatchProcessor) throws IOException {
        // Make sure this was the first occurrence of the given relativePath on the classpath to enable masking
        final ClassInfo classInfo = newClassInfo(relativePath, classpathEltIdx);
        if (classInfo == null) {
            // This relative path was already encountered earlier on the classpath
            if (FastClasspathScanner.verbose) {
                Log.log("Duplicate file on classpath, ignoring all but first instance: " + relativePath);
            }
        } else {
            ClassfileBinaryParser.readClassInfoFromClassfileHeader(relativePath, inputStream, classInfo,
                    classNameToStaticFieldnameToMatchProcessor);
        }
    }
}
