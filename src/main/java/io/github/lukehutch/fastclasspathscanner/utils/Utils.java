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
package io.github.lukehutch.fastclasspathscanner.utils;

import io.github.lukehutch.fastclasspathscanner.classgraph.ClassInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

public class Utils {

    /** Returns true if the path ends with a JAR extension */
    public static boolean isJar(final String path) {
        final String pathLower = path.toLowerCase();
        return pathLower.endsWith(".jar") || pathLower.endsWith(".zip") || pathLower.endsWith(".war")
                || pathLower.endsWith(".car");
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Convert a collection into a sorted list. */
    @SafeVarargs
    public static <T extends Comparable<T>> ArrayList<T> sortedCopy(final Collection<T>... collections) {
        final ArrayList<T> copy = new ArrayList<>();
        for (final Collection<T> collection : collections) {
            copy.addAll(collection);
        }
        Collections.sort(copy);
        return copy;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Strip Scala companion class suffixes from class name. */
    private static String scalaBaseClassName(final String scalaClassName) {
        if (scalaClassName != null && scalaClassName.endsWith("$")) {
            return scalaClassName.substring(0, scalaClassName.length() - 1);
        } else if (scalaClassName != null && scalaClassName.endsWith("$class")) {
            return scalaClassName.substring(0, scalaClassName.length() - 6);
        } else {
            return scalaClassName;
        }
    }

    /**
     * Merge ClassInfo for Scala's companion objects (ending in "$") and trait methods class (ending in "$class")
     * into the ClassInfo object for the base class that they are associated with.
     * 
     * N.B. it's possible that some of these cases will never be needed (e.g. the base class seems to have the
     * annotations, while the "$" class gets the annotations). For now, just be exhaustive and merge all Scala
     * auxiliary classes into one ClassInfo node.
     */
    public static Collection<ClassInfo> mergeScalaAuxClasses(final Collection<ClassInfo> classInfoFromScan) {
        final HashMap<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        final ArrayList<ClassInfo> companionObjectClassInfo = new ArrayList<>();
        for (final ClassInfo classInfo : classInfoFromScan) {
            // Remove "$" and "$class" suffix from names of superclasses, interfaces and annotations of all classes
            for (int i = 0; i < classInfo.superclassNames.size(); i++) {
                classInfo.superclassNames.set(i, scalaBaseClassName(classInfo.superclassNames.get(i)));
            }
            if (classInfo.interfaceNames != null) {
                for (int i = 0; i < classInfo.interfaceNames.size(); i++) {
                    classInfo.interfaceNames.set(i, scalaBaseClassName(classInfo.interfaceNames.get(i)));
                }
            }
            if (classInfo.annotationNames != null) {
                for (int i = 0; i < classInfo.annotationNames.size(); i++) {
                    classInfo.annotationNames.set(i, scalaBaseClassName(classInfo.annotationNames.get(i)));
                }
            }
            if (classInfo.className.endsWith("$") || classInfo.className.endsWith("$class")) {
                companionObjectClassInfo.add(classInfo);
            } else {
                classNameToClassInfo.put(classInfo.className, classInfo);
            }
        }
        // Merge ClassInfo for classes with suffix "$" and "$class" into base class that doesn't have the suffix  
        for (final ClassInfo companionClassInfo : companionObjectClassInfo) {
            final String classNameRaw = companionClassInfo.className;
            final String className = classNameRaw.endsWith("$class") ? classNameRaw.substring(0,
                    classNameRaw.length() - 6) : classNameRaw.substring(0, classNameRaw.length() - 1);
            if (!classNameToClassInfo.containsKey(className)) {
                // Couldn't find base class -- rename companion object and store it in place of base class
                companionClassInfo.className = className;
                classNameToClassInfo.put(className, companionClassInfo);
            } else {
                // Otherwise Merge companion class fields into base class' ClassInfo
                final ClassInfo baseClassInfo = classNameToClassInfo.get(className);
                baseClassInfo.isInterface |= companionClassInfo.isInterface;
                baseClassInfo.isAnnotation |= companionClassInfo.isAnnotation;
                baseClassInfo.superclassNames.addAll(companionClassInfo.superclassNames);
                // Reuse or merge the interface and annotation lists
                if (baseClassInfo.interfaceNames == null) {
                    baseClassInfo.interfaceNames = companionClassInfo.interfaceNames;
                } else if (companionClassInfo.interfaceNames != null) {
                    baseClassInfo.interfaceNames.addAll(companionClassInfo.interfaceNames);
                }
                if (baseClassInfo.annotationNames == null) {
                    baseClassInfo.annotationNames = companionClassInfo.annotationNames;
                } else if (companionClassInfo.annotationNames != null) {
                    baseClassInfo.annotationNames.addAll(companionClassInfo.annotationNames);
                }
            }
        }
        return classNameToClassInfo.values();
    }
}
