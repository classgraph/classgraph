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
import java.util.HashMap;
import java.util.HashSet;

/** The DAG node representing an annotation. */
class AnnotationNode extends DAGNode {
    /** All classes annotated with this annotation */
    HashSet<String> annotatedClassNames = new HashSet<>();

    /** All annotations annotated with this annotation */
    ArrayList<String> annotatedAnnotationNames = new ArrayList<>();

    /** The class defining an annotation was encountered on the classpath. */
    public AnnotationNode(final String annotationName) {
        super(annotationName);
    }

    /** Add an annotated annotation to this meta-annotation. */
    public void addAnnotatedAnnotation(String annotatedAnnotationName) {
        annotatedAnnotationNames.add(annotatedAnnotationName);
    }

    /** Add an annotated class to this annotation. */
    public void addAnnotatedClass(String annotatedClassName) {
        annotatedClassNames.add(annotatedClassName);
    }

    /** Resolve annotation names at end of classpath scanning. */
    public void resolveAnnotationNames(final HashMap<String, AnnotationNode> annotationNameToAnnotationNode) {
        for (String annotatedAnnotation : annotatedAnnotationNames) {
            AnnotationNode annotatedAnnotationNode = annotationNameToAnnotationNode.get(annotatedAnnotation);
            if (annotatedAnnotationNode != null) {
                addSubNode(annotatedAnnotationNode);
            }
        }
    }
}