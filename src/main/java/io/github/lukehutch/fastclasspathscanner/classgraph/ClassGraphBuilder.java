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

    public List<String> getClassesWithAnnotation(String annotationName) {
        ArrayList<String> classes = annotationNameToClassName.get(annotationName);
        if (classes == null) {
            return Collections.emptyList();
        }
        return classes;
    }

    public List<String> getClassesImplementing(String interfaceName) {
        ArrayList<String> classes = interfaceNameToClassNames.get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        }
        return classes;
    }

    public List<String> getSubclassesOf(String className) {
        ArrayList<String> subclasses = new ArrayList<>();
        ClassNode classNode = classNameToClassNode.get(className);
        if (classNode != null) {
            for (DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
        }
        return subclasses;
    }

    public List<String> getSuperclassesOf(String className) {
        ArrayList<String> superclasses = new ArrayList<>();
        ClassNode classNode = classNameToClassNode.get(className);
        if (classNode != null) {
            for (DAGNode subNode : classNode.allSuperNodes) {
                superclasses.add(subNode.name);
            }
        }
        return superclasses;
    }

    public List<String> getSubinterfacesOf(String interfaceName) {
        ArrayList<String> subinterfaces = new ArrayList<>();
        InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
        if (interfaceNode != null) {
            for (DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
        }
        return subinterfaces;
    }

    public List<String> getSuperinterfacesOf(String interfaceName) {
        ArrayList<String> superinterfaces = new ArrayList<>();
        InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
        if (interfaceNode != null) {
            for (DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
        }
        return superinterfaces;
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Link a class to its superclass and to the interfaces it implements, and save the class annotations. */
    public void linkToSuperclassAndInterfaces(String className, String superclassName, ArrayList<String> interfaces,
            HashSet<String> annotations) {
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
    public void linkToSuperinterfaces(String interfaceName, ArrayList<String> superInterfaces) {

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
            for (String superInterfaceName : superInterfaces) {
                // Look up InterfaceNode objects for superinterfaces, and connect them to this interface
                InterfaceNode superInterfaceNode = interfaceNameToInterfaceNode.get(superInterfaceName);
                if (superInterfaceNode == null) {
                    // The superinterface of this interface has not yet been encountered on the classpath
                    interfaceNameToInterfaceNode.put(superInterfaceName, superInterfaceNode =
                            new InterfaceNode(superInterfaceName, thisInterfaceInfo));
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
        ArrayList<DAGNode> classNodeTopoOrder = DAGNode.topoSort(classNameToClassNode.values());

        // Accumulate all superclasses of each class by traversing from highest to lowest class
        for (int i = 0, n = classNodeTopoOrder.size(); i < n; i++) {
            DAGNode classNode = classNodeTopoOrder.get(i);
            HashSet<DAGNode> allSuperNodes = new HashSet<>(classNode.allSuperNodes);
            for (DAGNode superclassNode : classNode.allSuperNodes) {
                allSuperNodes.addAll(superclassNode.allSuperNodes);
            }
            classNode.allSuperNodes = allSuperNodes;
        }

        // Accumulate all subclasses of each class by traversing from lowest to highest class
        for (int i = classNodeTopoOrder.size() - 1; i >= 0; --i) {
            DAGNode classNode = classNodeTopoOrder.get(i);
            HashSet<DAGNode> allSubNodes = new HashSet<>(classNode.allSubNodes);
            for (DAGNode subclassNode : classNode.allSubNodes) {
                allSubNodes.addAll(subclassNode.allSubNodes);
            }
            classNode.allSubNodes = allSubNodes;
        }

        // Perform topological sort on interface DAG
        ArrayList<DAGNode> interfaceNodeTopoOrder = DAGNode.topoSort(interfaceNameToInterfaceNode.values());

        // Accumulate all superinterfaces of each interface by traversing from highest to lowest interface
        for (int i = 0, n = interfaceNodeTopoOrder.size(); i < n; i++) {
            DAGNode interfaceNode = interfaceNodeTopoOrder.get(i);
            HashSet<DAGNode> allSuperNodes = new HashSet<>(interfaceNode.allSuperNodes);
            for (DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                allSuperNodes.addAll(superinterfaceNode.allSuperNodes);
            }
            interfaceNode.allSuperNodes = allSuperNodes;
        }

        // Accumulate all subinterfaces of each interface by traversing from lowest to highest interface
        for (int i = interfaceNodeTopoOrder.size() - 1; i >= 0; --i) {
            DAGNode interfaceNode = interfaceNodeTopoOrder.get(i);
            HashSet<DAGNode> allSubNodes = new HashSet<>(interfaceNode.allSubNodes);
            for (DAGNode subinterfaceNode : interfaceNode.allSubNodes) {
                allSubNodes.addAll(subinterfaceNode.allSubNodes);
            }
            interfaceNode.allSubNodes = allSubNodes;
        }

        // Reverse mapping from annotation to classes that have the annotation.
        HashMap<String, HashSet<DAGNode>> annotationToClassNodes = new HashMap<>();

        // Reverse mapping from interface to classes that implement the interface.
        HashMap<String, HashSet<DAGNode>> interfaceToClassNodes = new HashMap<>();

        // Create reverse mapping from annotation to the names of classes that have the annotation,
        // and from interface names to the names of classes that implement the interface.
        for (DAGNode classDAGNode : classNodeTopoOrder) {
            ClassNode classNode = (ClassNode) classDAGNode;
            if (classNode.annotationNames != null) {
                // Map from annotation back to classes that have the annotation
                for (String annotation : classNode.annotationNames) {
                    HashSet<DAGNode> classList = annotationToClassNodes.get(annotation);
                    if (classList == null) {
                        annotationToClassNodes.put(annotation, classList = new HashSet<>());
                    }
                    classList.add(classDAGNode);
                }
            }

            if (classNode.interfaceNames != null) {
                // Map from interface back to classes that implement the interface
                HashSet<String> interfacesAndSuperinterfaces = new HashSet<>();
                for (String interfaceName : classNode.interfaceNames) {
                    // Any class that implements an interface also implements all its superinterfaces
                    interfacesAndSuperinterfaces.add(interfaceName);
                    InterfaceNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
                    if (interfaceNode != null) {
                        for (DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                            interfacesAndSuperinterfaces.add(superinterfaceNode.name);
                        }
                    }
                }
                for (String interfaceName : interfacesAndSuperinterfaces) {
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
        for (DAGNode interfaceNode : interfaceNodeTopoOrder) {
            // Get all classes that implement this interface
            HashSet<DAGNode> implementingClasses = interfaceToClassNodes.get(interfaceNode.name);
            if (implementingClasses != null) {
                // Get the union of all subclasses of all classes that implement this interface
                HashSet<DAGNode> allSubClasses = new HashSet<DAGNode>(implementingClasses);
                for (DAGNode implementingClass : implementingClasses) {
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
        for (Entry<String, HashSet<DAGNode>> ent : annotationToClassNodes.entrySet()) {
            ArrayList<String> classNameList = new ArrayList<>();
            annotationNameToClassName.put(ent.getKey(), classNameList);
            HashSet<DAGNode> classNodes = ent.getValue();
            if (classNodes != null) {
                for (DAGNode classNode : classNodes) {
                    classNameList.add(classNode.name);
                }
            }
        }
        for (Entry<String, HashSet<DAGNode>> ent : interfaceToClassNodes.entrySet()) {
            ArrayList<String> classNameList = new ArrayList<>();
            interfaceNameToClassNames.put(ent.getKey(), classNameList);
            HashSet<DAGNode> classNodes = ent.getValue();
            if (classNodes != null) {
                for (DAGNode classNode : classNodes) {
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
