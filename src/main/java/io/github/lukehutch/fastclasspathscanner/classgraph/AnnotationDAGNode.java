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

/** A DAG node representing an annotation class. */
class AnnotationDAGNode extends DAGNode {
    /** The nodes corresponding to classes annotated by this annotation. */
    ArrayList<DAGNode> annotatedClassNodes = new ArrayList<>(2);

    /** A DAG node representing an annotation class. */
    public AnnotationDAGNode(final ClassInfo classInfo) {
        super(classInfo);
    }

    /** Connect this annotation node to a class it annotates. */
    public void addAnnotatedClass(final DAGNode annotatedClassNode) {
        this.annotatedClassNodes.add(annotatedClassNode);
    }

    @Override
    public void connect(final HashMap<String, DAGNode> classNameToDAGNode) {
        super.connect(classNameToDAGNode);

        if (classInfo.annotationNames != null) {
            for (final String metaAnnotationName : classInfo.annotationNames) {
                final DAGNode metaAnnotationNode = classNameToDAGNode.get(metaAnnotationName);
                if (metaAnnotationNode != null) {
                    // Annotations on an annotation class are meta-annotations -- add them as supernodes
                    metaAnnotationNode.addSubNode(this);
                }
            }
        }
    }
}
