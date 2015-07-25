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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

public class ClassGraphBuilder {

    /** A map from fully-qualified class name to the corresponding ClassNode object. */
    private final HashMap<String, ClassNode> classNameToClassNode = new HashMap<>();

    /** A map from fully-qualified class name to the corresponding InterfaceNode object. */
    private final HashMap<String, InterfaceNode> interfaceNameToInterfaceNode = new HashMap<>();

    /** Reverse mapping from annotation to classes that have the annotation. */
    private final HashMap<String, ArrayList<String>> annotationNameToClassName = new HashMap<>();

    /** Reverse mapping from interface to classes that implement the interface */
    private final HashMap<String, ArrayList<String>> interfaceNameToClassNames = new HashMap<>();

    // -----------------------------------------------------------------------------------------------------------------

    /** Return the names of all classes with the named class annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classes = annotationNameToClassName.get(annotationName);
        if (classes == null) {
            return Collections.emptyList();
        }
        return classes;
    }

    /** Return the names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final ArrayList<String> classes = interfaceNameToClassNames.get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        }
        return classes;
    }

    /** Return the names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        final ArrayList<String> subclasses = new ArrayList<>();
        final ClassNode classNode = classNameToClassNode.get(className);
        if (classNode != null) {
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
        }
        return subclasses;
    }

    /** Return the names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        final ArrayList<String> superclasses = new ArrayList<>();
        final ClassNode classNode = classNameToClassNode.get(className);
        if (classNode != null) {
            for (final DAGNode subNode : classNode.allSuperNodes) {
                superclasses.add(subNode.name);
            }
        }
        return superclasses;
    }

    /** Return the names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final ArrayList<String> subinterfaces = new ArrayList<>();
        final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
        if (interfaceNode != null) {
            for (final DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
        }
        return subinterfaces;
    }

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        final ArrayList<String> superinterfaces = new ArrayList<>();
        final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
        if (interfaceNode != null) {
            for (final DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
        }
        return superinterfaces;
    }

    /** Return all class names reached during the scan. */
    public Set<String> getNamesOfAllClasses() {
        return classNameToClassNode.keySet();
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Link a class to its superclass and to the interfaces it implements, and save the class annotations. */
    public void linkToSuperclassAndInterfaces(final String className, final String superclassName,
            final ArrayList<String> interfaces, final HashSet<String> annotations) {
        // Save the info recovered from the classfile for a class

        // Look up ClassNode object for this class
        ClassNode thisClassNode = classNameToClassNode.get(className);
        if (thisClassNode == null) {
            // This class has not been encountered before on the classpath 
            classNameToClassNode.put(className, thisClassNode = new ClassNode(className, interfaces, annotations));
        } else {
            // This is the first time this class has been encountered on the classpath, but
            // it was previously cited as a superclass of another class
            thisClassNode.encounter(interfaces, annotations);
        }

        // Look up ClassNode object for superclass, and connect it to this class
        ClassNode superclassNode = classNameToClassNode.get(superclassName);
        if (superclassNode == null) {
            // The superclass of this class has not yet been encountered on the classpath
            classNameToClassNode.put(superclassName, superclassNode = new ClassNode(superclassName, thisClassNode));
        } else {
            superclassNode.addSubNode(thisClassNode);
        }
    }

    /** Save the mapping from an interface to its superinterfaces. */
    public void linkToSuperinterfaces(final String interfaceName, final ArrayList<String> superInterfaces) {

        // Look up InterfaceNode for this interface
        InterfaceNode thisInterfaceInfo = interfaceNameToInterfaceNode.get(interfaceName);
        if (thisInterfaceInfo == null) {
            // This interface has not been encountered before on the classpath 
            interfaceNameToInterfaceNode.put(interfaceName, thisInterfaceInfo = new InterfaceNode(interfaceName));
        } else {
            // This is the first time this interface has been encountered on the classpath, but
            // it was previously cited as a superinterface of another interface
            thisInterfaceInfo.encounter();
        }

        if (superInterfaces != null) {
            for (final String superInterfaceName : superInterfaces) {
                // Look up InterfaceNode objects for superinterfaces, and connect them to this interface
                InterfaceNode superInterfaceNode = interfaceNameToInterfaceNode.get(superInterfaceName);
                if (superInterfaceNode == null) {
                    // The superinterface of this interface has not yet been encountered on the classpath
                    interfaceNameToInterfaceNode.put(superInterfaceName, superInterfaceNode = new InterfaceNode(
                            superInterfaceName, thisInterfaceInfo));
                } else {
                    superInterfaceNode.addSubNode(thisInterfaceInfo);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Find all superclasses and subclasses for each class and superinterfaces and subinterfaces of each interface.
     * Called once all classes have been read.
     */
    public void finalizeNodes() {
        if (classNameToClassNode.isEmpty() && interfaceNameToInterfaceNode.isEmpty()) {
            // If no classes or interfaces were matched, there is no hierarchy to build
            return;
        }

        // Perform topological sort on class tree
        final ArrayList<DAGNode> classNodeTopoOrder = DAGNode.topoSort(classNameToClassNode.values());

        // Accumulate all superclasses of each class by traversing from highest to lowest class
        for (int i = 0, n = classNodeTopoOrder.size(); i < n; i++) {
            final DAGNode classNode = classNodeTopoOrder.get(i);
            final HashSet<DAGNode> allSuperNodes = new HashSet<>(classNode.allSuperNodes);
            for (final DAGNode superclassNode : classNode.allSuperNodes) {
                allSuperNodes.addAll(superclassNode.allSuperNodes);
            }
            classNode.allSuperNodes = allSuperNodes;
        }

        // Accumulate all subclasses of each class by traversing from lowest to highest class
        for (int i = classNodeTopoOrder.size() - 1; i >= 0; --i) {
            final DAGNode classNode = classNodeTopoOrder.get(i);
            final HashSet<DAGNode> allSubNodes = new HashSet<>(classNode.allSubNodes);
            for (final DAGNode subclassNode : classNode.allSubNodes) {
                allSubNodes.addAll(subclassNode.allSubNodes);
            }
            classNode.allSubNodes = allSubNodes;
        }

        // Perform topological sort on interface DAG
        final ArrayList<DAGNode> interfaceNodeTopoOrder = DAGNode.topoSort(interfaceNameToInterfaceNode.values());

        // Accumulate all superinterfaces of each interface by traversing from highest to lowest interface
        for (int i = 0, n = interfaceNodeTopoOrder.size(); i < n; i++) {
            final DAGNode interfaceNode = interfaceNodeTopoOrder.get(i);
            final HashSet<DAGNode> allSuperNodes = new HashSet<>(interfaceNode.allSuperNodes);
            for (final DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                allSuperNodes.addAll(superinterfaceNode.allSuperNodes);
            }
            interfaceNode.allSuperNodes = allSuperNodes;
        }

        // Accumulate all subinterfaces of each interface by traversing from lowest to highest interface
        for (int i = interfaceNodeTopoOrder.size() - 1; i >= 0; --i) {
            final DAGNode interfaceNode = interfaceNodeTopoOrder.get(i);
            final HashSet<DAGNode> allSubNodes = new HashSet<>(interfaceNode.allSubNodes);
            for (final DAGNode subinterfaceNode : interfaceNode.allSubNodes) {
                allSubNodes.addAll(subinterfaceNode.allSubNodes);
            }
            interfaceNode.allSubNodes = allSubNodes;
        }

        // Reverse mapping from annotation to classes that have the annotation.
        final HashMap<String, HashSet<DAGNode>> annotationToClassNodes = new HashMap<>();

        // Reverse mapping from interface to classes that implement the interface.
        final HashMap<String, HashSet<DAGNode>> interfaceToClassNodes = new HashMap<>();

        // Create reverse mapping from annotation to the names of classes that have the annotation,
        // and from interface names to the names of classes that implement the interface.
        for (final DAGNode classDAGNode : classNodeTopoOrder) {
            final ClassNode classNode = (ClassNode) classDAGNode;
            if (classNode.annotationNames != null) {
                // Map from annotation back to classes that have the annotation
                for (final String annotation : classNode.annotationNames) {
                    HashSet<DAGNode> classList = annotationToClassNodes.get(annotation);
                    if (classList == null) {
                        annotationToClassNodes.put(annotation, classList = new HashSet<>());
                    }
                    classList.add(classDAGNode);
                }
            }

            if (classNode.interfaceNames != null) {
                // Map from interface back to classes that implement the interface
                final HashSet<String> interfacesAndSuperinterfaces = new HashSet<>();
                for (final String interfaceName : classNode.interfaceNames) {
                    // Any class that implements an interface also implements all its superinterfaces
                    interfacesAndSuperinterfaces.add(interfaceName);
                    final InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
                    if (interfaceNode != null) {
                        for (final DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                            interfacesAndSuperinterfaces.add(superinterfaceNode.name);
                        }
                    }
                }
                for (final String interfaceName : interfacesAndSuperinterfaces) {
                    // Add mapping from interface back to implementing class
                    HashSet<DAGNode> classList = interfaceToClassNodes.get(interfaceName);
                    if (classList == null) {
                        interfaceToClassNodes.put(interfaceName, classList = new HashSet<>());
                    }
                    classList.add(classDAGNode);
                }
            }
        }

        // Classes that subclass another class that implements an interface also implement the same interface.
        // Add these to the mapping from interface back to the classes that implement the interface.
        for (final DAGNode interfaceNode : interfaceNodeTopoOrder) {
            // Get all classes that implement this interface
            final HashSet<DAGNode> implementingClasses = interfaceToClassNodes.get(interfaceNode.name);
            if (implementingClasses != null) {
                // Get the union of all subclasses of all classes that implement this interface
                final HashSet<DAGNode> allSubClasses = new HashSet<DAGNode>(implementingClasses);
                for (final DAGNode implementingClass : implementingClasses) {
                    allSubClasses.addAll(implementingClass.allSubNodes);
                }
                // Add to the mapping from the interface to each subclass of a class that implements the interface
                HashSet<DAGNode> classList = interfaceToClassNodes.get(interfaceNode.name);
                if (classList == null) {
                    interfaceToClassNodes.put(interfaceNode.name, classList = new HashSet<>());
                }
                classList.addAll(allSubClasses);
            }
        }

        // Convert annotation and interface mappings to String->String 
        for (final Entry<String, HashSet<DAGNode>> ent : annotationToClassNodes.entrySet()) {
            final ArrayList<String> classNameList = new ArrayList<>();
            annotationNameToClassName.put(ent.getKey(), classNameList);
            final HashSet<DAGNode> classNodes = ent.getValue();
            if (classNodes != null) {
                for (final DAGNode classNode : classNodes) {
                    classNameList.add(classNode.name);
                }
            }
        }
        for (final Entry<String, HashSet<DAGNode>> ent : interfaceToClassNodes.entrySet()) {
            final ArrayList<String> classNameList = new ArrayList<>();
            interfaceNameToClassNames.put(ent.getKey(), classNameList);
            final HashSet<DAGNode> classNodes = ent.getValue();
            if (classNodes != null) {
                for (final DAGNode classNode : classNodes) {
                    classNameList.add(classNode.name);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    public void reset() {
        classNameToClassNode.clear();
        interfaceNameToInterfaceNode.clear();
        annotationNameToClassName.clear();
        interfaceNameToClassNames.clear();
    }
}
