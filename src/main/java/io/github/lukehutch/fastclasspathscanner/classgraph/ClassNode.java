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
import java.util.HashSet;

/**
 * The DAG node representing a class. The DAG of classes is technically a tree because of single inheritance.
 */
class ClassNode extends DAGNode {
    /** All interfaces */
    ArrayList<String> interfaceNames = new ArrayList<>();

    /** All annotations */
    HashSet<String> annotationNames = new HashSet<>();

    /** This class was encountered on the classpath. */
    public ClassNode(String className, ArrayList<String> interfaceNames, HashSet<String> annotationNames) {
        super(className);
        this.name = className;
        this.encounter(interfaceNames, annotationNames);
    }

    /** A subclass of this class was encountered on the classpath, but this class has not yet been encountered. */
    public ClassNode(String className, ClassNode subclass) {
        super(className, subclass);
    }

    /** This class was previously cited as a superclass, and now has itself been encountered on the classpath. */
    public void encounter(ArrayList<String> interfaceNames, HashSet<String> annotationNames) {
        super.encounter();
        this.interfaceNames = interfaceNames;
        this.annotationNames = annotationNames;
    }

    /** Connect this class to a subclass. */
    public void addSubNode(ClassNode subclass) {
        super.addSubNode(subclass);
        if (subclass.directSuperNodes.size() > 1) {
            throw new RuntimeException(subclass.name + " has two superclasses: "
                    + subclass.directSuperNodes.get(0).name + ", " + subclass.directSuperNodes.get(1).name);
        }
    }
}