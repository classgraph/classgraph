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

import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

public class ClassGraphBuilder {
    /**
     * Names of classes encountered so far during a scan. If the same classname is encountered more than once, the
     * second and subsequent instances are ignored, because they are masked by the earlier occurrence in the
     * classpath.
     */
    private final HashSet<String> classesEncounteredSoFarDuringScan = new HashSet<>();

    /** A map from class name to the corresponding ClassNode object. */
    private final LazyMap<String, ClassNode> classNameToClassNode = //
    new LazyMap<String, ClassNode>() {
        @Override
        public void initialize() {
            findTransitiveClosure(map.values());
        }
    };

    /** A map from class name to the corresponding InterfaceNode object. */
    private final LazyMap<String, InterfaceNode> interfaceNameToInterfaceNode = //
    new LazyMap<String, InterfaceNode>() {
        @Override
        public void initialize() {
            findTransitiveClosure(map.values());
        }
    };

    /** A map from class name to the corresponding AnnotationNode object. */
    private final LazyMap<String, AnnotationNode> annotationNameToAnnotationNode = //
    new LazyMap<String, AnnotationNode>() {
        @Override
        public void initialize() {
            // Resolve annotation names to annotation references
            for (AnnotationNode annotationNode : map.values()) {
                annotationNode.resolveAnnotationNames(map);
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
            for (Entry<String, HashSet<DAGNode>> ent : interfaceNameToClassNodesSet.entrySet()) {
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
            for (AnnotationNode annotationNode : allAnnotationNodes()) {
                for (DAGNode subNode : annotationNode.allSubNodes) {
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
            for (AnnotationNode annotationNode : allAnnotationNodes()) {
                for (DAGNode subNode : annotationNode.allSubNodes) {
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
            for (Entry<String, HashSet<String>> ent : annotationNameToAnnotatedClassNamesSet.resolve().entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    /** Mapping from class name to the names of annotations and meta-annotations on the class. */
    private final LazyMap<String, ArrayList<String>> classNameToAnnotationNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        public void initialize() {
            for (Entry<String, HashSet<String>> ent : MultiSet.invert(
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
            for (Entry<String, HashSet<String>> ent : annotationNameToAnnotatedAnnotationNamesSet.resolve()
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
            for (Entry<String, HashSet<String>> ent : MultiSet.invert(
                    annotationNameToAnnotatedAnnotationNamesSet.resolve()).entrySet()) {
                MultiMap.putAll(map, ent.getKey(), ent.getValue());
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A map from classname, to static final field name, to a StaticFinalFieldMatchProcessor that should be called
     * if that class name and static final field name is encountered during scan.
     */
    private final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> //
    classNameToStaticFieldnameToMatchProcessor = new HashMap<>();

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered in a class with the given fully-qualified classname while reading a classfile header.
     */
    public void addStaticFinalFieldProcessor(String className, String fieldName,
            StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        HashMap<String, StaticFinalFieldMatchProcessor> fieldNameToMatchProcessor = //
        classNameToStaticFieldnameToMatchProcessor.get(className);
        if (fieldNameToMatchProcessor == null) {
            classNameToStaticFieldnameToMatchProcessor.put(className, fieldNameToMatchProcessor = new HashMap<>(2));
        }
        fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
    }

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

    /** Link a class to its superclass and to the interfaces it implements, and save the class annotations. */
    private void linkClass(final String superclassName, final ArrayList<String> interfaces, //
            final String className) {
        // Look up ClassNode object for this class
        HashMap<String, ClassNode> map = classNameToClassNode.getRawMap();
        ClassNode classNode = map.get(className);
        if (classNode == null) {
            // This class has not been encountered before on the classpath 
            map.put(className, classNode = new ClassNode(className, interfaces));
        } else {
            // This is the first time this class has been encountered on the classpath (since the class
            // name must be unique, and we have already checked for classpath masking), but this class was
            // previously cited as a superclass of another interface
            classNode.addInterfaces(interfaces);
        }

        if (superclassName != null) {
            // Look up ClassNode object for superclass, and connect it to this class
            ClassNode superclassNode = map.get(superclassName);
            if (superclassNode == null) {
                // The superclass of this class has not yet been encountered on the classpath
                map.put(superclassName, superclassNode = new ClassNode(superclassName, classNode));
            } else {
                superclassNode.addSubNode(classNode);
            }
        }
    }

    /** Save the mapping from an interface to its superinterfaces. */
    private void linkInterface(final ArrayList<String> superInterfaceNames, final String interfaceName) {
        // Look up InterfaceNode for this interface
        HashMap<String, InterfaceNode> map = interfaceNameToInterfaceNode.getRawMap();
        InterfaceNode interfaceNode = map.get(interfaceName);
        if (interfaceNode == null) {
            // This interface has not been encountered before on the classpath 
            map.put(interfaceName, interfaceNode = new InterfaceNode(interfaceName));
        }

        if (superInterfaceNames != null) {
            for (final String superInterfaceName : superInterfaceNames) {
                // Look up InterfaceNode objects for superinterfaces, and connect them to this interface
                InterfaceNode superInterfaceNode = map.get(superInterfaceName);
                if (superInterfaceNode == null) {
                    // The superinterface of this interface has not yet been encountered on the classpath
                    map.put(superInterfaceName, superInterfaceNode = new InterfaceNode(superInterfaceName,
                            interfaceNode));
                } else {
                    superInterfaceNode.addSubNode(interfaceNode);
                }
            }
        }
    }

    /** Save the mapping from a class or annotation to its annotations or meta-annotations. */
    private void linkAnnotation(String annotationName, String annotatedClassName, //
            boolean annotatedClassIsAnnotation) {
        // Look up AnnotationNode for this annotation, or create node if it doesn't exist
        HashMap<String, AnnotationNode> map = annotationNameToAnnotationNode.getRawMap();
        AnnotationNode annotationNode = map.get(annotationName);
        if (annotationNode == null) {
            map.put(annotationName, annotationNode = new AnnotationNode(annotationName));
        }
        // If the annotated class is itself an annotation
        if (annotatedClassIsAnnotation) {
            // Look up AnnotationNode for the annotated class, or create node if it doesn't exist
            AnnotationNode annotatedAnnotationNode = map.get(annotatedClassName);
            if (annotatedAnnotationNode == null) {
                map.put(annotatedClassName, annotatedAnnotationNode = new AnnotationNode(annotatedClassName));
            }
            // Link meta-annotation to annotation
            annotationNode.addAnnotatedAnnotation(annotatedClassName);
        } else {
            // Link annotation to class
            annotationNode.addAnnotatedClass(annotatedClassName);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the upwards and downwards transitive closure for each node in a graph. Assumes the graph is a DAG in
     * general, but handles cycles (which may occur in the case of meta-annotations).
     */
    private static void findTransitiveClosure(Collection<? extends DAGNode> nodes) {
        // Find top nodes as initial active set
        HashSet<DAGNode> activeTopDownNodes = new HashSet<>();
        for (DAGNode node : nodes) {
            if (node.directSuperNodes.isEmpty()) {
                activeTopDownNodes.addAll(node.directSubNodes);
            }
        }
        // Use DP-style "wavefront" to find top-down transitive closure, even if there are cycles
        while (!activeTopDownNodes.isEmpty()) {
            HashSet<DAGNode> activeTopDownNodesNext = new HashSet<>(activeTopDownNodes.size());
            for (DAGNode node : activeTopDownNodes) {
                boolean changed = node.allSuperNodes.addAll(node.directSuperNodes);
                for (DAGNode superNode : node.directSuperNodes) {
                    changed |= node.allSuperNodes.addAll(superNode.allSuperNodes);
                }
                if (changed) {
                    for (DAGNode subNode : node.directSubNodes) {
                        activeTopDownNodesNext.add(subNode);
                    }
                }
            }
            activeTopDownNodes = activeTopDownNodesNext;
        }

        // Find bottom nodes as initial active set
        HashSet<DAGNode> activeBottomUpNodes = new HashSet<>();
        for (DAGNode node : nodes) {
            if (node.directSubNodes.isEmpty()) {
                activeBottomUpNodes.addAll(node.directSuperNodes);
            }
        }
        // Use DP-style "wavefront" to find bottom-up transitive closure, even if there are cycles
        while (!activeBottomUpNodes.isEmpty()) {
            HashSet<DAGNode> activeBottomUpNodesNext = new HashSet<>(activeBottomUpNodes.size());
            for (DAGNode node : activeBottomUpNodes) {
                boolean changed = node.allSubNodes.addAll(node.directSubNodes);
                for (DAGNode subNode : node.directSubNodes) {
                    changed |= node.allSubNodes.addAll(subNode.allSubNodes);
                }
                if (changed) {
                    for (DAGNode superNode : node.directSuperNodes) {
                        activeBottomUpNodesNext.add(superNode);
                    }
                }
            }
            activeBottomUpNodes = activeBottomUpNodesNext;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Clear all the data structures to be ready for another scan. */
    public void reset() {
        classesEncounteredSoFarDuringScan.clear();

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
     * Read annotation entry from classfile.
     */
    private String readAnnotation(final DataInputStream inp, final Object[] constantPool) throws IOException {
        final String annotationFieldDescriptor = readRefdString(inp, constantPool);
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom/xyz/Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1)
                    .replace('/', '.');
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        final int numElementValuePairs = inp.readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            inp.skipBytes(2); // element_name_index
            readAnnotationElementValue(inp, constantPool);
        }
        return annotationClassName;
    }

    /**
     * Read annotation element value from classfile.
     */
    private void readAnnotationElementValue(final DataInputStream inp, final Object[] constantPool)
            throws IOException {
        final int tag = inp.readUnsignedByte();
        switch (tag) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
        case 's':
            // const_value_index
            inp.skipBytes(2);
            break;
        case 'e':
            // enum_const_value
            inp.skipBytes(4);
            break;
        case 'c':
            // class_info_index
            inp.skipBytes(2);
            break;
        case '@':
            // Complex (nested) annotation
            readAnnotation(inp, constantPool);
            break;
        case '[':
            // array_value
            final int count = inp.readUnsignedShort();
            for (int l = 0; l < count; ++l) {
                // Nested annotation element value
                readAnnotationElementValue(inp, constantPool);
            }
            break;
        default:
            // System.err.println("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
            break;
        }
    }

    /**
     * Read as usigned short constant pool reference, then look up the string in the constant pool.
     */
    private static String readRefdString(final DataInputStream inp, final Object[] constantPool) //
            throws IOException {
        return (String) constantPool[inp.readUnsignedShort()];
    }

    /**
     * Directly examine contents of classfile binary header.
     * 
     * @param verbose
     */
    public void readClassInfoFromClassfileHeader(final InputStream inputStream, boolean verbose) //
            throws IOException {
        final DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 1024));

        // Magic
        if (inp.readInt() != 0xCAFEBABE) {
            // Not classfile
            return;
        }

        // Minor version
        inp.readUnsignedShort();
        // Major version
        inp.readUnsignedShort();

        // Constant pool count (1-indexed, zeroth entry not used)
        final int cpCount = inp.readUnsignedShort();
        // Constant pool
        final Object[] constantPool = new Object[cpCount];
        final int[] indirectStringRef = new int[cpCount];
        Arrays.fill(indirectStringRef, -1);
        for (int i = 1; i < cpCount; ++i) {
            final int tag = inp.readUnsignedByte();
            switch (tag) {
            case 1: // Modified UTF8
                constantPool[i] = inp.readUTF();
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
                constantPool[i] = inp.readInt();
                break;
            case 4: // float
                constantPool[i] = inp.readFloat();
                break;
            case 5: // long
                constantPool[i] = inp.readLong();
                i++; // double slot
                break;
            case 6: // double
                constantPool[i] = inp.readDouble();
                i++; // double slot
                break;
            case 7: // Class
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRef[i] = inp.readUnsignedShort();
                break;
            case 9: // field ref
            case 10: // method ref
            case 11: // interface ref
            case 12: // name and type
                inp.skipBytes(4); // two shorts
                break;
            case 15: // method handle
                inp.skipBytes(3);
                break;
            case 16: // method type
                inp.skipBytes(2);
                break;
            case 18: // invoke dynamic
                inp.skipBytes(4);
                break;
            default:
                // System.err.println("Unkown tag value for constant pool entry: " + tag);
                break;
            }
        }
        // Resolve indirection of string references now that all the strings have been read
        // (allows forward references to strings before they have been encountered)
        for (int i = 1; i < cpCount; i++) {
            if (indirectStringRef[i] >= 0) {
                constantPool[i] = constantPool[indirectStringRef[i]];
            }
        }

        // Access flags
        final int flags = inp.readUnsignedShort();
        final boolean isInterface = (flags & 0x0200) != 0;
        final boolean isAnnotation = (flags & 0x2000) != 0;

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String className = readRefdString(inp, constantPool).replace('/', '.');
        if (className.equals("java.lang.Object")) {
            // java.lang.Object doesn't have a superclass to be linked to, can simply return
            return;
        }

        // Determine if this fully-qualified class name has already been encountered during this scan
        if (!classesEncounteredSoFarDuringScan.add(className)) {
            // If so, skip this classfile, because the earlier class with the same name as this one
            // occurred earlier on the classpath, so it masks this one.
            return;
        }

        // Superclass name, with slashes replaced with dots
        final String superclassName = readRefdString(inp, constantPool).replace('/', '.');

        // Look up static field name match processors given class name 
        final HashMap<String, StaticFinalFieldMatchProcessor> staticFieldnameToMatchProcessor //
        = classNameToStaticFieldnameToMatchProcessor.get(className);

        // Interfaces
        final int interfaceCount = inp.readUnsignedShort();
        final ArrayList<String> interfaces = interfaceCount > 0 ? new ArrayList<String>(interfaceCount) : null;
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(readRefdString(inp, constantPool).replace('/', '.'));
        }

        // Fields
        final int fieldCount = inp.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            final int accessFlags = inp.readUnsignedShort();
            // See http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final boolean isStaticFinal = (accessFlags & 0x0018) == 0x0018;
            final String fieldName = readRefdString(inp, constantPool);
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor //
            = staticFieldnameToMatchProcessor != null ? staticFieldnameToMatchProcessor.get(fieldName) : null;
            final String descriptor = readRefdString(inp, constantPool);
            final int attributesCount = inp.readUnsignedShort();
            if (!isStaticFinal && staticFinalFieldMatchProcessor != null) {
                // Requested to match a field that is not static or not final
                System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                        + ": cannot match requested field " + className + "." + fieldName
                        + " because it is either not static or not final");
            } else if (!isStaticFinal || staticFinalFieldMatchProcessor == null) {
                // Not matching this static final field, just skip field attributes rather than parsing them
                for (int j = 0; j < attributesCount; j++) {
                    inp.skipBytes(2); // attribute_name_index
                    final int attributeLength = inp.readInt();
                    inp.skipBytes(attributeLength);
                }
            } else {
                // Look for static final fields that match one of the requested names,
                // and that are initialized with a constant value
                boolean foundConstantValue = false;
                for (int j = 0; j < attributesCount; j++) {
                    final String attributeName = readRefdString(inp, constantPool);
                    final int attributeLength = inp.readInt();
                    if (attributeName.equals("ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        Object constValue = constantPool[inp.readUnsignedShort()];
                        // byte, char, short and boolean constants are all stored as 4-byte int
                        // values -- coerce and wrap in the proper wrapper class with autoboxing
                        switch (descriptor) {
                        case "B":
                            // Convert byte store in Integer to Byte
                            constValue = ((Integer) constValue).byteValue();
                            break;
                        case "C":
                            // Convert char stored in Integer to Character
                            constValue = (char) ((Integer) constValue).intValue();
                            break;
                        case "S":
                            // Convert char stored in Integer to Short
                            constValue = ((Integer) constValue).shortValue();
                            break;
                        case "Z":
                            // Convert char stored in Integer to Boolean
                            constValue = ((Integer) constValue).intValue() != 0;
                            break;
                        case "I":
                        case "J":
                        case "F":
                        case "D":
                        case "Ljava.lang.String;":
                            // Field is int, long, float, double or String => object is already in correct
                            // wrapper type (Integer, Long, Float, Double or String), nothing to do
                            break;
                        default:
                            // Should never happen:
                            // constant values can only be stored as an int, long, float, double or String
                            break;
                        }
                        // Call static final field match processor
                        if (verbose) {
                            Log.log("Found static final field " + className + "." + fieldName + " = " + constValue);
                        }
                        staticFinalFieldMatchProcessor.processMatch(className, fieldName, constValue);
                        foundConstantValue = true;
                    } else {
                        inp.skipBytes(attributeLength);
                    }
                    if (!foundConstantValue) {
                        System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                                + ": Requested static final field " + className + "." + fieldName
                                + "is not initialized with a constant literal value, so there is no "
                                + "initializer value in the constant pool of the classfile");
                    }
                }
            }
        }

        // Methods
        final int methodCount = inp.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            inp.skipBytes(6); // access_flags, name_index, descriptor_index
            final int attributesCount = inp.readUnsignedShort();
            for (int j = 0; j < attributesCount; j++) {
                inp.skipBytes(2); // attribute_name_index
                final int attributeLength = inp.readInt();
                inp.skipBytes(attributeLength);
            }
        }

        // Attributes (including class annotations)
        int attributesCount = inp.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final String attributeName = readRefdString(inp, constantPool);
            final int attributeLength = inp.readInt();
            if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                final int annotationCount = inp.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    final String annotationName = readAnnotation(inp, constantPool);
                    // Ignore java.lang.annotation annotations (Target/Retention/Documented etc.)
                    if (!annotationName.startsWith("java.lang.annotation.")) {
                        linkAnnotation(annotationName, className, /* classIsAnnotation = */isAnnotation);
                    }
                }
            } else {
                inp.skipBytes(attributeLength);
            }
        }

        if (isAnnotation) {
            // If a class is itself an annotation (rather than having an annotation), ignore -- handled above.
        } else if (isInterface) {
            linkInterface(/* superInterfaces = */interfaces, /* interfaceName = */className);
        } else {
            linkClass(superclassName, interfaces, className);
        }
    }
}
