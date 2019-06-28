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

import java.lang.reflect.Field;

/**
 * Class for wrapping an enum constant value (split into class name and constant name), as used as an annotation
 * parameter value.
 */
public class AnnotationEnumValue extends ScanResultObject implements Comparable<AnnotationEnumValue> {
    /** The class name. */
    private String className;

    /** The value name. */
    private String valueName;

    /** Default constructor for deserialization. */
    AnnotationEnumValue() {
        super();
    }

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
     * @return The fully-qualified name of the enum constant value, i.e. ({@link #getClassName()} +
     *         {#getValueName()}).
     */
    public String getName() {
        return className + "." + valueName;
    }

    /**
     * Loads the enum class, instantiates the enum constants for the class, and returns the enum constant value
     * represented by this {@link AnnotationEnumValue}.
     * 
     * @param ignoreExceptions
     *            If true, ignore classloading exceptions and return null on failure.
     * @return The enum constant value represented by this {@link AnnotationEnumValue}
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false, or if the enum constant is
     *             invalid.
     */
    public Object loadClassAndReturnEnumValue(final boolean ignoreExceptions) throws IllegalArgumentException {
        final Class<?> classRef = super.loadClass(ignoreExceptions);
        if (classRef == null) {
            if (ignoreExceptions) {
                return null;
            } else {
                throw new IllegalArgumentException("Enum class " + className + " could not be loaded");
            }
        }
        if (!classRef.isEnum()) {
            throw new IllegalArgumentException("Class " + className + " is not an enum");
        }
        Field field;
        try {
            field = classRef.getDeclaredField(valueName);
        } catch (final ReflectiveOperationException | SecurityException e) {
            throw new IllegalArgumentException("Could not find enum constant " + toString(), e);
        }
        if (!field.isEnumConstant()) {
            throw new IllegalArgumentException("Field " + toString() + " is not an enum constant");
        }
        try {
            return field.get(null);
        } catch (final ReflectiveOperationException | SecurityException e) {
            throw new IllegalArgumentException("Field " + toString() + " is not accessible", e);
        }
    }

    /**
     * Loads the enum class, instantiates the enum constants for the class, and returns the enum constant value
     * represented by this {@link AnnotationEnumValue}.
     * 
     * @return The enum constant value represented by this {@link AnnotationEnumValue}
     * @throws IllegalArgumentException
     *             if the class could not be loaded, or the enum constant is invalid.
     */
    public Object loadClassAndReturnEnumValue() throws IllegalArgumentException {
        return loadClassAndReturnEnumValue(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final AnnotationEnumValue o) {
        final int diff = className.compareTo(o.className);
        return diff == 0 ? valueName.compareTo(o.valueName) : diff;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof AnnotationEnumValue)) {
            return false;
        }
        return compareTo((AnnotationEnumValue) obj) == 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return className.hashCode() * 11 + valueName.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return className + "." + valueName;
    }
}