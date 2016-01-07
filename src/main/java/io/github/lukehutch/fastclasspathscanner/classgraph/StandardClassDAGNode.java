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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** A DAG node representing a standard class (a non-interface, non-annotation class). */
class StandardClassDAGNode extends DAGNode {

    /** The nodes corresponding to interfaces implemented by this class. */
    ArrayList<ImplementedInterfaceDAGNode> implementedInterfaceClassNodes = new ArrayList<>(2);

    /** The nodes corresponding to classes annotated by this annotation. */
    HashSet<DAGNode> whitelistedFieldTypeNodes = new HashSet<>(2);

    /** A DAG node representing a standard class (a non-interface, non-annotation class). */
    public StandardClassDAGNode(final ClassInfo classInfo) {
        super(classInfo);
    }

    /** Connect this standard class node to the node corresponding to an interface it implements. */
    public void addImplementedInterface(final ImplementedInterfaceDAGNode implementedInterfaceNode) {
        this.implementedInterfaceClassNodes.add(implementedInterfaceNode);
    }

    /** Connect this standard class node to the node corresponding to an interface it implements. */
    public void addWhitelistedFieldType(final DAGNode whitelistedFieldTypeNode) {
        this.whitelistedFieldTypeNodes.add(whitelistedFieldTypeNode);
    }

    @Override
    public void connect(final HashMap<String, DAGNode> classNameToDAGNode) {
        super.connect(classNameToDAGNode);

        // Connect classes to the interfaces they implement
        if (classInfo.interfaceNames != null) {
            for (final String interfaceName : classInfo.interfaceNames) {
                // interfaceNode will usually be of type InterfaceDAGNode, but it could be of type AnnotationDAGNode
                // if the code implements an annotation (annotations are actually interfaces, see issue #38).
                final ImplementedInterfaceDAGNode interfaceNode = (ImplementedInterfaceDAGNode) classNameToDAGNode
                        .get(interfaceName);
                if (interfaceNode != null) {
                    this.addImplementedInterface(interfaceNode);
                }
            }
        }

        // Connect any annotations on this class to this class 
        if (classInfo.annotationNames != null) {
            for (final String annotationName : classInfo.annotationNames) {
                final AnnotationDAGNode annotationNode = (AnnotationDAGNode) classNameToDAGNode.get(annotationName);
                if (annotationNode != null) {
                    annotationNode.addAnnotatedClass(this);
                }
            }
        }

        // Connect class to types of fields that are within a whitelisted (non-blacklisted) package prefix
        if (classInfo.whitelistedFieldTypes != null) {
            for (final String whitelistedFieldTypeName : classInfo.whitelistedFieldTypes) {
                final DAGNode typeNode = classNameToDAGNode.get(whitelistedFieldTypeName);
                if (typeNode != null) {
                    this.addWhitelistedFieldType(typeNode);
                }
            }
        }
    }
}
