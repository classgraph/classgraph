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
package io.github.lukehutch.fastclasspathscanner.classfileparser;

import java.util.ArrayList;
import java.util.HashSet;

public class ClassInfo {
    public String className;
    public boolean isInterface;
    public boolean isAnnotation;
    // There will usually only be one superclass, except in the case of Scala, which compiles companion objects
    public ArrayList<String> superclassNames = new ArrayList<>(1);

    public ArrayList<String> interfaceNames;
    public ArrayList<String> annotationNames;
    public HashSet<String> fieldTypes;

    public ClassInfo(final String className, final boolean isInterface, final boolean isAnnotation,
            final String superclassName) {
        this.className = className;
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
        this.superclassNames.add(superclassName);
    }
}
