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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;

public class ClassGraphBuilder {
    private final ArrayList<ClassInfo> allClassInfo;

    public ClassGraphBuilder(final Collection<ClassInfo> classInfoFromScan) {
        this.allClassInfo = new ArrayList<>(handleScalaAuxClasses(classInfoFromScan));
    }

    /**
     * Merge ClassInfo for Scala's companion objects (ending in "$") and trait methods class (ending in "$class")
     * into the ClassInfo object for the base class that they are associated with.
     * 
     * N.B. it's possible that some of these cases will never be needed (e.g. the base class seems to have the
     * annotations, while the "$" class gets the annotations). For now, just be exhaustive and merge all Scala
     * auxiliary classes into one ClassInfo node.
     */
    private static Collection<ClassInfo> handleScalaAuxClasses(final Collection<ClassInfo> classInfoFromScan) {
        final HashMap<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        final ArrayList<ClassInfo> companionObjectClassInfo = new ArrayList<>();
        for (final ClassInfo classInfo : classInfoFromScan) {
            // Remove "$" and "$class" suffix from names of superclasses, interfaces and annotations of all classes
            if (classInfo.superclassName != null && classInfo.superclassName.endsWith("$")) {
                classInfo.superclassName = classInfo.superclassName.substring(0,
                        classInfo.superclassName.length() - 1);
            }
            if (classInfo.interfaceNames != null) {
                for (int i = 0; i < classInfo.interfaceNames.size(); i++) {
                    final String ifaceName = classInfo.interfaceNames.get(i);
                    if (ifaceName.endsWith("$")) {
                        classInfo.interfaceNames.set(i, ifaceName.substring(0, ifaceName.length() - 1));
                    } else if (ifaceName.endsWith("$class")) {
                        classInfo.interfaceNames.set(i, ifaceName.substring(0, ifaceName.length() - 6));
                    }

                }
            }
            if (classInfo.annotationNames != null) {
                for (int i = 0; i < classInfo.annotationNames.size(); i++) {
                    final String annName = classInfo.annotationNames.get(i);
                    if (annName.endsWith("$")) {
                        classInfo.annotationNames.set(i, annName.substring(0, annName.length() - 1));
                    } else if (annName.endsWith("$class")) {
                        classInfo.annotationNames.set(i, annName.substring(0, annName.length() - 6));
                    }
                }
            }
            if (classInfo.className.endsWith("$") || classInfo.className.endsWith("$class")) {
                companionObjectClassInfo.add(classInfo);
            } else {
                classNameToClassInfo.put(classInfo.className, classInfo);
            }
        }
        // Merge ClassInfo for classes with suffix "$" and "$class" into base class that doesn't have the suffix  
        for (final ClassInfo companionClassInfo : companionObjectClassInfo) {
            final String classNameRaw = companionClassInfo.className;
            final String className = classNameRaw.endsWith("$class")
                    ? classNameRaw.substring(0, classNameRaw.length() - 6)
                    : classNameRaw.substring(0, classNameRaw.length() - 1);
            if (!classNameToClassInfo.containsKey(className)) {
                // Couldn't find base class -- rename companion object and store it in place of base class
                companionClassInfo.className = className;
                classNameToClassInfo.put(className, companionClassInfo);
            } else {
                // Otherwise Merge companion class fields into base class' ClassInfo
                final ClassInfo baseClassInfo = classNameToClassInfo.get(className);
                baseClassInfo.isInterface |= companionClassInfo.isInterface;
                baseClassInfo.isAnnotation |= companionClassInfo.isAnnotation;
                if (baseClassInfo.superclassName == null && companionClassInfo.superclassName != null) {
                    baseClassInfo.superclassName = companionClassInfo.superclassName;
                } else if (baseClassInfo.superclassName != null && companionClassInfo.superclassName != null
                        && !baseClassInfo.superclassName.equals(companionClassInfo.superclassName)) {
                    Log.log("Could not fully merge Scala companion class and base class: " + baseClassInfo.className
                            + " has superclass " + baseClassInfo.superclassName + "; "
                            + companionClassInfo.className + " has superclass "
                            + companionClassInfo.superclassName);
                }
                // Reuse or merge the interface and annotation lists
                if (baseClassInfo.interfaceNames == null) {
                    baseClassInfo.interfaceNames = companionClassInfo.interfaceNames;
                } else if (companionClassInfo.interfaceNames != null) {
                    baseClassInfo.interfaceNames.addAll(companionClassInfo.interfaceNames);
                }
                if (baseClassInfo.annotationNames == null) {
                    baseClassInfo.annotationNames = companionClassInfo.annotationNames;
                } else if (companionClassInfo.annotationNames != null) {
                    baseClassInfo.annotationNames.addAll(companionClassInfo.annotationNames);
                }
            }
        }
        return classNameToClassInfo.values();
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
                        DAGNode.getOrNew(map, classInfo.superclassName).addSubNode(classNode);
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
                // Look up or create interface node if this is an interface
                final DAGNode classNodeIfInterface = classInfo.isInterface
                        ? DAGNode.getOrNew(map, classInfo.className) : null;
                if (classInfo.interfaceNames != null) {
                    // Look up or create InterfaceNode objects for superinterfaces
                    for (final String implementedInterfaceName : classInfo.interfaceNames) {
                        final DAGNode implementedInterfaceNode = DAGNode.getOrNew(map, implementedInterfaceName);
                        // If the class implementing the interface is itself an interface, it is a subinterface.
                        if (classNodeIfInterface != null) {
                            implementedInterfaceNode.addSubNode(classNodeIfInterface);
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
                if (classInfo.isAnnotation) {
                    // If the current class is an annotation
                    final DAGNode classNode = DAGNode.getOrNew(map, classInfo.className);
                    if (classInfo.annotationNames != null) {
                        // This is an annotation with meta-annotations
                        for (final String annotationName : classInfo.annotationNames) {
                            // Add the meta-annotation as a super-node of the annotation it annotates
                            DAGNode.getOrNew(map, annotationName).addSubNode(classNode);
                        }
                    }
                } else {
                    if (classInfo.annotationNames != null) {
                        // If the current class is not an annotation, but has its own annotations
                        for (final String annotationName : classInfo.annotationNames) {
                            // Cross-link annotation to the current class
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

    /** The sorted unique names of all classes, interfaces and annotations reached during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            final ArrayList<String> classNames = new ArrayList<>(classNameToClassNode.keySet());
            Collections.sort(classNames);
            return classNames;
        };
    };

    /** Return the sorted unique names of all classes, interfaces and annotations reached during the scan. */
    public List<String> getNamesOfAllClasses() {
        return namesOfAllClasses.get("");
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
            LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNamesSet,
                    annotationNameToAnnotationNode));

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
