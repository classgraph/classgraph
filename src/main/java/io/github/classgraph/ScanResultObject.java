/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A superclass of objects accessible from a {@link ScanResult} that are associated with a {@link ClassInfo} object.
 */
abstract class ScanResultObject {

    /** The scan result. */
    transient protected ScanResult scanResult;

    /** The associated {@link ClassInfo} object. */
    private transient ClassInfo classInfo;

    /** The class ref, once the class is loaded. */
    private transient Class<?> classRef;

    /**
     * Set ScanResult backreferences in info objects after scan has completed.
     *
     * @param scanResult
     *            the scan result
     */
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
    }

    /**
     * Get the names of all referenced classes.
     *
     * @return the referenced class names
     */
    Set<String> getReferencedClassNames() {
        final Set<String> allReferencedClassNames = new LinkedHashSet<>();
        getReferencedClassNames(allReferencedClassNames);
        // Remove references to java.lang.Object
        allReferencedClassNames.remove("java.lang.Object");
        return allReferencedClassNames;
    }

    /**
     * Get any class names referenced in type descriptors of this object.
     *
     * @param refdClassNames
     *            the referenced class names
     */
    abstract void getReferencedClassNames(Set<String> refdClassNames);

    /**
     * The name of the class (used by {@link #getClassInfo()} to fetch the {@link ClassInfo} object for the class).
     * 
     * @return The class name.
     */
    protected abstract String getClassName();

    /**
     * Get the {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     * encountered during scanning (i.e. no ClassInfo object was created for the class during scanning). N.B. even
     * if this method returns null, {@link #loadClass()} may be able to load the referenced class by name.
     * 
     * @return The {@link ClassInfo} object for the referenced class.
     */
    ClassInfo getClassInfo() {
        if (classInfo == null) {
            if (scanResult == null) {
                return null;
            }
            final String className = getClassName();
            if (className == null) {
                throw new IllegalArgumentException("Class name is not set");
            }
            classInfo = scanResult.getClassInfo(className);
        }
        return classInfo;
    }

    /**
     * Get the class name by calling getClassInfo().getName(), or as a fallback, by calling getClassName().
     *
     * @return the class name
     */
    private String getClassInfoNameOrClassName() {
        String className;
        ClassInfo ci = getClassInfo();
        if (ci == null) {
            ci = classInfo;
        }
        if (ci != null) {
            // Get class name from getClassInfo().getName() 
            className = ci.getName();
        } else {
            // Get class name from getClassName() 
            className = getClassName();
        }
        if (className == null) {
            throw new IllegalArgumentException("Class name is not set");
        }
        return className;
    }

    /**
     * Load the class named returned by {@link #getClassInfo()}, or if that returns null, the class named by
     * {@link #getClassName()}. Returns a {@code Class<?>} reference for the class, cast to the requested superclass
     * or interface type.
     *
     * @param <T>
     *            the superclass or interface type
     * @param superclassOrInterfaceType
     *            The type to cast the resulting class reference to.
     * @param ignoreExceptions
     *            If true, ignore classloading exceptions and return null on failure.
     * @return The {@code Class<?>} reference for the referenced class, or null if the class could not be loaded (or
     *         casting failed) and ignoreExceptions is true.
     * @throws IllegalArgumentException
     *             if the class could not be loaded or cast, and ignoreExceptions was false.
     */
    <T> Class<T> loadClass(final Class<T> superclassOrInterfaceType, final boolean ignoreExceptions) {
        if (classRef == null) {
            classRef = scanResult.loadClass(getClassInfoNameOrClassName(), superclassOrInterfaceType,
                    ignoreExceptions);
        }
        @SuppressWarnings("unchecked")
        final Class<T> classT = (Class<T>) classRef;
        return classT;
    }

    /**
     * Load the class named returned by {@link #getClassInfo()}, or if that returns null, the class named by
     * {@link #getClassName()}. Returns a {@code Class<?>} reference for the class, cast to the requested superclass
     * or interface type.
     *
     * @param <T>
     *            the superclass or interface type
     * @param superclassOrInterfaceType
     *            The type to cast the resulting class reference to.
     * @return The {@code Class<?>} reference for the referenced class, or null if the class could not be loaded (or
     *         casting failed) and ignoreExceptions is true.
     * @throws IllegalArgumentException
     *             if the class could not be loaded or cast, and ignoreExceptions was false.
     */
    <T> Class<T> loadClass(final Class<T> superclassOrInterfaceType) {
        return loadClass(superclassOrInterfaceType, /* ignoreExceptions = */ false);
    }

    /**
     * Load the class named returned by {@link #getClassInfo()}, or if that returns null, the class named by
     * {@link #getClassName()}. Returns a {@code Class<?>} reference for the class.
     * 
     * @param ignoreExceptions
     *            If true, ignore classloading exceptions and return null on failure.
     * @return The {@code Class<?>} reference for the referenced class, or null if the class could not be loaded and
     *         ignoreExceptions is true.
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false.
     */
    Class<?> loadClass(final boolean ignoreExceptions) {
        if (classRef == null) {
            classRef = scanResult.loadClass(getClassInfoNameOrClassName(), ignoreExceptions);
        }
        return classRef;
    }

    /**
     * Load the class named returned by {@link #getClassInfo()}, or if that returns null, the class named by
     * {@link #getClassName()}. Returns a {@code Class<?>} reference for the class.
     * 
     * @return The {@code Class<?>} reference for the referenced class, or null if the class could not be loaded and
     *         ignoreExceptions is true.
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false.
     */
    Class<?> loadClass() {
        return loadClass(/* ignoreExceptions = */ false);
    }
}