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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner;

import java.util.Set;

abstract class ScanResultObject {
    transient protected ScanResult scanResult;

    private transient ClassInfo classInfo;

    protected abstract String getClassName();

    /** Return the {@link ClassInfo} object associated with this {@link ScanResultObject}. */
    ClassInfo getClassInfo() {
        final String className = getClassName();
        if (className == null) {
            throw new IllegalArgumentException("Class name is not set");
        }
        if (classInfo == null) {
            classInfo = scanResult.getClassInfo(className);
            if (classInfo == null) {
                return null;
            }
        }
        return classInfo;
    }

    /**
     * Load the referenced class, if not already loaded, returning a {@code Class<?>} reference for the referenced
     * class. First tries calling {@link #getClassInfo()} to get a {@link ClassInfo} object, then calls
     * {@link ClassInfo#loadClass()}, so that the right {@link ClassLoader} is called. If {@link #getClassInfo()}
     * returns null (meaning that the class is an "external class" that was not encountered during the scan), then
     * instead calls {@link ScanResult#loadClass(String, boolean)}.
     * 
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded.
     */
    Class<?> loadClass() {
        final String className = getClassName();
        if (className == null) {
            throw new IllegalArgumentException("Class name is not set");
        }
        final ClassInfo classInfo = getClassInfo();
        if (classInfo != null) {
            return classInfo.loadClass();
        } else {
            return scanResult.loadClass(className, /* ignoreExceptions = */ false);
        }
    }

    /** Get any class names referenced in type descriptors of this object. */
    abstract void getClassNamesFromTypeDescriptors(Set<String> classNames);

    /** Set ScanResult backreferences in info objects after scan has completed. */
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
    }
}