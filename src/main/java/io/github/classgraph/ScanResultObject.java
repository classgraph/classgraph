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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A superclass of objects accessible from a {@link ScanResult} that are
 * associated with a {@link ClassInfo} object.
 */
abstract class ScanResultObject {
    /** The scan result. */
    protected @Nullable ScanResult scanResult;

    /** The associated {@link ClassInfo} object. */
    private @Nullable ClassInfo classInfo;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set ScanResult backreferences in info objects after scan has completed.
     *
     * @param scanResult the scan result
     */
    void setScanResult(final @Nullable ScanResult scanResult) {
        this.scanResult = scanResult;
    }

    /**
     * Get the {@link ScanResult} this object was obtained from, for use in code
     * paths that can only be reached through a completed scan.
     *
     * @return the scan result
     * @throws NullPointerException if the scan result has not been set (i.e. if
     *                              this object was created outside a scan).
     */
    final ScanResult scanResult() {
        return Objects.requireNonNull(scanResult);
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced by this object.
     *
     * @param log the log
     * @return the referenced class info.
     */
    final Set<ClassInfo> findReferencedClassInfo(final @Nullable LogNode log) {
        final Set<ClassInfo> refdClassInfo = new LinkedHashSet<>();
        if (scanResult != null) {
            findReferencedClassInfo(scanResult.classNameToClassInfo, refdClassInfo, log);
        }
        return refdClassInfo;
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced by this object.
     *
     * @param classNameToClassInfo the map from class name to {@link ClassInfo}.
     * @param refdClassInfo        the referenced class info
     * @param log                  the log
     */
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        final var ci = getClassInfo();
        if (ci != null) {
            refdClassInfo.add(ci);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The name of the class (used by {@code getClassInfo()} to fetch the
     * {@link ClassInfo} object for the class).
     * 
     * @return The class name.
     */
    protected abstract @Nullable String getClassName();

    /**
     * Get the {@link ClassInfo} object for the referenced class, or null if the
     * referenced class was not encountered during scanning (i.e. no ClassInfo
     * object was created for the class during scanning).
     *
     * @return The {@link ClassInfo} object for the referenced class.
     */
    @Nullable
    ClassInfo getClassInfo() {
        if (classInfo == null) {
            if (scanResult == null) {
                return null;
            }
            final var className = getClassName();
            if (className == null) {
                throw new IllegalArgumentException("Class name is not set");
            }
            classInfo = scanResult.getClassInfo(className);
        }
        return classInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Render to string.
     *
     * @param useSimpleNames if true, use just the simple name of each class.
     * @param buf            the buf
     */
    protected abstract void toString(final boolean useSimpleNames, StringBuilder buf);

    /**
     * Render to string, with simple names for classes if useSimpleNames is true.
     *
     * @param useSimpleNames if true, use just the simple name of each class.
     * @return the string representation.
     */
    String toString(final boolean useSimpleNames) {
        final StringBuilder buf = new StringBuilder();
        toString(useSimpleNames, buf);
        return buf.toString();
    }

    /**
     * Render to string, using only <a href=
     * "https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/lang/Class.html#getSimpleName()">simple
     * names</a> for classes.
     *
     * @return the string representation, using simple names for classes.
     */
    public String toStringWithSimpleNames() {
        final StringBuilder buf = new StringBuilder();
        toString(/* useSimpleNames = */ true, buf);
        return buf.toString();
    }

    /**
     * Render to string.
     *
     * @return the string representation.
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(/* useSimpleNames = */ false, buf);
        return buf.toString();
    }
}