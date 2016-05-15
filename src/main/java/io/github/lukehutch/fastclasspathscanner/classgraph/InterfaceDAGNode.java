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

import java.util.HashMap;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;

/**
 * A DAG node representing a class that is an interface and not an annotation. (Annotations are actually interfaces,
 * so they can be implemented.)
 */
class InterfaceDAGNode extends DAGNode {
    /** A DAG node representing an interface class. */
    public InterfaceDAGNode(final ClassInfo classInfo) {
        super(classInfo);
    }

    /** Creates a placeholder node for a reference to a class outside a whitelisted package. */
    public InterfaceDAGNode(final String name) {
        super(name);
    }

    @Override
    public void connect(final HashMap<String, DAGNode> classNameToDAGNode) {
        super.connect(classNameToDAGNode);

        if (classInfo != null) {
            // Connect interfaces to their superinterfaces
            if (classInfo.interfaceNames != null) {
                for (final String superinterfaceName : classInfo.interfaceNames) {
                    final DAGNode superinterfaceNode = classNameToDAGNode.get(superinterfaceName);
                    if (superinterfaceNode != null) {
                        superinterfaceNode.addSubNode(this);
                    }
                }
            }
            // Connect any annotations on this interface to this interface 
            if (classInfo.annotationNames != null) {
                for (final String annotationName : classInfo.annotationNames) {
                    final AnnotationDAGNode annotationNode = getDAGNodeOfType(classNameToDAGNode, annotationName,
                            AnnotationDAGNode.class);
                    if (annotationNode != null) {
                        annotationNode.addAnnotatedClass(this);
                    }
                }
            }
        }
    }
}
