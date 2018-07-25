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

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Class for wrapping an enum constant value (split into class name and constant name) referenced inside an
 * annotation.
 */
public class AnnotationEnumValue extends ScanResultObject implements Comparable<AnnotationEnumValue> {
    String className;
    String valueName;

    /** Default constructor for deserialization. */
    AnnotationEnumValue() {
    }

    /**
     * @param className
     *            The enum class name.
     * @param constValueName
     *            The enum const value name.
     */
    public AnnotationEnumValue(final String className, final String constValueName) {
        this.className = className;
        this.valueName = constValueName;
    }

    /** Return the name of the enum class. */
    @Override
    public String getClassName() {
        return className;
    }

    /** Return the name of the enum const value. */
    public String getValueName() {
        return valueName;
    }

    /**
     * Get the fully-qualified name of the enum const value, i.e. (getClassName() + getConstValueName()).
     * 
     * @return The fully-qualified name of the enum const value, i.e. (getClassName() + getConstValueName()).
     */
    public String getName() {
        return className + "." + valueName;
    }

    /**
     * Loads the enum class, if it has not yet been loaded, instantiates the enum constants, and returns the enum
     * constant value represented by this {@link AnnotationEnumValue}.
     * 
     * @return The enum constant value.
     * @throws IllegalArgumentException
     *             if the class could not be loaded, or the enum constant is invalid.
     */
    public Object loadClassAndReturnEnumValue() throws IllegalArgumentException {
        final Class<?> classRef = super.loadClass();
        if (!classRef.isEnum()) {
            throw new IllegalArgumentException("Class " + className + " is not an enum");
        }
        Field field;
        try {
            field = classRef.getDeclaredField(valueName);
        } catch (NoSuchFieldException | SecurityException e) {
            throw new IllegalArgumentException("Could not find enum constant " + toString(), e);
        }
        if (!field.isEnumConstant()) {
            throw new IllegalArgumentException("Field " + toString() + " is not an enum constant");
        }
        try {
            return field.get(null);
        } catch (final IllegalAccessException e) {
            throw new IllegalArgumentException("Field " + toString() + " is not accessible", e);
        }
    }

    /** Get the names of any classes referenced in the annotation parameters. */
    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> referencedClassNames) {
        referencedClassNames.add(className);
    }

    @Override
    public int compareTo(final AnnotationEnumValue o) {
        final int diff = className.compareTo(o.className);
        return diff == 0 ? valueName.compareTo(o.valueName) : diff;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof AnnotationEnumValue)) {
            return false;
        }
        return compareTo((AnnotationEnumValue) o) == 0;
    }

    @Override
    public int hashCode() {
        return className.hashCode() * 11 + valueName.hashCode();
    }

    @Override
    public String toString() {
        return className + "." + valueName;
    }
}