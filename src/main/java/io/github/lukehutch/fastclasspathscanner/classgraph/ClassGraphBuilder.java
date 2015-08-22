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
import io.github.lukehutch.fastclasspathscanner.utils.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Reverse mapping from fully-qualified annotation names to the names of classes that have the annotation. */
    private final HashMap<String, ArrayList<String>> annotationNameToClassNames = new HashMap<>();

    /** Reverse mapping from fully-qualified interface names to the names of classes that implement the interface */
    private final HashMap<String, ArrayList<String>> interfaceNameToClassNames = new HashMap<>();

    /**
     * Mapping from fully-qualified meta-annotation names to names of annotations that are annotated with the
     * meta-annotation.
     */
    private final HashMap<String, ArrayList<String>> metaAnnotationToAnnotatedAnnotationNames = new HashMap<>();

    /**
     * Mapping from fully-qualified meta-annotation names to classes that are annotated with the meta-annotation.
     */
    private final HashMap<String, ArrayList<String>> metaAnnotationToAnnotatedClassNames = new HashMap<>();

    /**
     * Fully-qualified names of classes encountered so far during a scan. If the same fully-qualified classname is
     * encountered more than once, the second and subsequent instances are ignored, because they are masked by the
     * earlier occurrence in the classpath.
     */
    private final HashSet<String> classesEncounteredSoFarDuringScan = new HashSet<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A map from fully-qualified classname, to static final field name, to a StaticFinalFieldMatchProcessor that
     * should be called if that class name and static final field name is encountered during scan.
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
            classNameToStaticFieldnameToMatchProcessor.put(className, fieldNameToMatchProcessor = new HashMap<>());
        }
        fieldNameToMatchProcessor.put(fieldName, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return the names of all classes with the named class annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classes = annotationNameToClassNames.get(annotationName);
        if (classes == null) {
            return Collections.emptyList();
        }
        return classes;
    }

    /**
     * Return the names of all classes with the named class meta-annotation (i.e. classes that are annotated with
     * the meta-annotation, or that are annotated with an annotation that is annotated with the meta-annotation).
     */
    public List<String> getNamesOfClassesWithMetaAnnotation(final String metaAnnotation) {
        ArrayList<String> classes = metaAnnotationToAnnotatedClassNames.get(metaAnnotation);
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

    // -------------------------------------------------------------------------------------------------------------

    /** Link a class to its superclass and to the interfaces it implements, and save the class annotations. */
    private void linkToSuperclassAndInterfaces(final String className, final String superclassName,
            final ArrayList<String> interfaces, final HashSet<String> annotations, boolean isAnnotation) {
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

        if (superclassName != null) {
            // Look up ClassNode object for superclass, and connect it to this class
            ClassNode superclassNode = classNameToClassNode.get(superclassName);
            if (superclassNode == null) {
                // The superclass of this class has not yet been encountered on the classpath
                classNameToClassNode.put(superclassName, superclassNode = new ClassNode(superclassName,
                        thisClassNode));
            } else {
                superclassNode.addSubNode(thisClassNode);
            }
        }
    }

    /** Save the mapping from an interface to its superinterfaces. */
    private void linkToSuperinterfaces(final String interfaceName, final ArrayList<String> superInterfaces) {
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

    /** Save the mapping from an annotation to its meta-annotations. */
    private void linkToMetaAnnotations(String annotationName, HashSet<String> metaAnnotations) {
        if (metaAnnotations != null) {
            for (String metaAnnotation : metaAnnotations) {
                ArrayList<String> annotatedAnnotations = metaAnnotationToAnnotatedAnnotationNames
                        .get(metaAnnotation);
                if (annotatedAnnotations == null) {
                    metaAnnotationToAnnotatedAnnotationNames.put(metaAnnotation,
                            annotatedAnnotations = new ArrayList<>(4));
                }
                annotatedAnnotations.add(annotationName);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find all superclasses and subclasses for each class and superinterfaces and subinterfaces of each interface.
     * Called once all classes have been read.
     */
    public void finalizeGraph() {
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
            annotationNameToClassNames.put(ent.getKey(), classNameList);
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

        // Map from meta-annotations to the classes annotated by the annotations annotated by the meta-annotations
        for (Entry<String, ArrayList<String>> ent : annotationNameToClassNames.entrySet()) {
            String annotationName = ent.getKey();
            ArrayList<String> classNames = ent.getValue();

            // Meta-annotations can be used to annotate classes directly --
            // start by adding all classes directly annotated by any annotation
            HashSet<String> annotatedClassNamesSet = new HashSet<>(classNames);

            // Add all classes that are annotated by annotations that are annotated by a meta-annotation
            ArrayList<String> annotatedAnnotationNames = metaAnnotationToAnnotatedAnnotationNames
                    .get(annotationName);
            if (annotatedAnnotationNames != null) {
                for (String annotatedAnnotationName : annotatedAnnotationNames) {
                    ArrayList<String> metaAnnotatedClassNames = annotationNameToClassNames
                            .get(annotatedAnnotationName);
                    if (metaAnnotatedClassNames != null) {
                        annotatedClassNamesSet.addAll(metaAnnotatedClassNames);
                    }
                }
            }
            metaAnnotationToAnnotatedClassNames.put(annotationName, new ArrayList<>(annotatedClassNamesSet));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    public void reset() {
        classNameToClassNode.clear();
        interfaceNameToInterfaceNode.clear();
        annotationNameToClassNames.clear();
        interfaceNameToClassNames.clear();
        classesEncounteredSoFarDuringScan.clear();
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
    private static String readRefdString(final DataInputStream inp, final Object[] constantPool) throws IOException {
        return (String) constantPool[inp.readUnsignedShort()];
    }

    /**
     * Directly examine contents of classfile binary header.
     * 
     * @param verbose
     */
    public void readClassInfoFromClassfileHeader(final InputStream inputStream, boolean verbose) throws IOException {
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
        final ArrayList<String> interfaces = interfaceCount > 0 ? new ArrayList<String>() : null;
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
        HashSet<String> annotations = null;
        final int attributesCount = inp.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final String attributeName = readRefdString(inp, constantPool);
            final int attributeLength = inp.readInt();
            if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                final int annotationCount = inp.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    final String annotationName = readAnnotation(inp, constantPool);
                    if (annotations == null) {
                        annotations = new HashSet<>();
                    }
                    annotations.add(annotationName);
                }
            } else {
                inp.skipBytes(attributeLength);
            }
        }

        if (isAnnotation) {
            linkToMetaAnnotations(/* annotationName = */className, /* metaAnnotations = */annotations);
        } else if (isInterface) {
            linkToSuperinterfaces(/* interfaceName = */className, /* superInterfaces = */interfaces);
        } else {
            linkToSuperclassAndInterfaces(className, superclassName, interfaces, annotations, isAnnotation);
        }
    }
}
