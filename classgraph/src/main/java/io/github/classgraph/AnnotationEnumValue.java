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

import org.jspecify.annotations.Nullable;

/**
 * Class for wrapping an enum constant value (split into class name and constant name), as used as an annotation
 * parameter value.
 */
public class AnnotationEnumValue extends ScanResultObject implements Comparable<AnnotationEnumValue> {
    /** The class name. */
    private final String className;

    /** The value name. */
    private final String valueName;

    /**
     * Constructor.
     *
     * @param className
     *            The enum class name.
     * @param constValueName
     *            The enum const value name.
     */
    AnnotationEnumValue(final String className, final String constValueName) {
        super();
        this.className = className;
        this.valueName = constValueName;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class name.
     *
     * @return The name of the enum class.
     */
    @Override
    public String getClassName() {
        return className;
    }

    /**
     * Get the value name.
     *
     * @return The name of the enum const value.
     */
    public String getValueName() {
        return valueName;
    }

    /**
     * Get the name.
     *
     * @return The fully-qualified name of the enum constant value, i.e. {@link #getClassName()} + {@code "."} +
     *         {@link #getValueName()}.
     */
    public String getName() {
        return className + "." + valueName;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final AnnotationEnumValue o) {
        final var diff = className.compareTo(o.className);
        return diff == 0 ? valueName.compareTo(o.valueName) : diff;
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final AnnotationEnumValue other)) {
            return false;
        }
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return className.hashCode() * 11 + valueName.hashCode();
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        buf.append(useSimpleNames ? ClassInfo.getSimpleName(className) : className);
        buf.append('.');
        buf.append(valueName);
    }
}