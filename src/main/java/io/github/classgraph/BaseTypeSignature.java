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

import java.util.Set;

import nonapi.io.github.classgraph.types.Parser;

/** A type signature for a base type (byte, char, double, float, int, long, short, boolean, or void). */
public class BaseTypeSignature extends TypeSignature {
    /** A base type (byte, char, double, float, int, long, short, boolean, or void). */
    private final String baseType;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param baseType
     *            the base type
     */
    BaseTypeSignature(final String baseType) {
        this.baseType = baseType;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the type as a string.
     *
     * @return The base type, such as "int", "float", or "void".
     */
    public String getTypeStr() {
        return baseType;
    }

    /**
     * Get the type.
     *
     * @return The class of the base type, such as int.class, float.class, or void.class.
     */
    public Class<?> getType() {
        switch (baseType) {
        case "byte":
            return byte.class;
        case "char":
            return char.class;
        case "double":
            return double.class;
        case "float":
            return float.class;
        case "int":
            return int.class;
        case "long":
            return long.class;
        case "short":
            return short.class;
        case "boolean":
            return boolean.class;
        case "void":
            return void.class;
        default:
            throw new IllegalArgumentException("Unknown base type " + baseType);
        }
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#loadClass()
     */
    @Override
    Class<?> loadClass() {
        return getType();
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#loadClass(java.lang.Class)
     */
    @Override
    <T> Class<T> loadClass(final Class<T> superclassOrInterfaceType) {
        final Class<?> type = getType();
        if (!superclassOrInterfaceType.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    "Primitive class " + baseType + " cannot be cast to " + superclassOrInterfaceType.getName());
        }
        @SuppressWarnings("unchecked")
        final Class<T> classT = (Class<T>) type;
        return classT;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a base type.
     *
     * @param parser
     *            the parser
     * @return the base type signature
     */
    static BaseTypeSignature parse(final Parser parser) {
        switch (parser.peek()) {
        case 'B':
            parser.next();
            return new BaseTypeSignature("byte");
        case 'C':
            parser.next();
            return new BaseTypeSignature("char");
        case 'D':
            parser.next();
            return new BaseTypeSignature("double");
        case 'F':
            parser.next();
            return new BaseTypeSignature("float");
        case 'I':
            parser.next();
            return new BaseTypeSignature("int");
        case 'J':
            parser.next();
            return new BaseTypeSignature("long");
        case 'S':
            parser.next();
            return new BaseTypeSignature("short");
        case 'Z':
            parser.next();
            return new BaseTypeSignature("boolean");
        case 'V':
            parser.next();
            return new BaseTypeSignature("void");
        default:
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.HierarchicalTypeSignature#findReferencedClassNames(java.util.Set)
     */
    @Override
    void findReferencedClassNames(final Set<String> classNameListOut) {
        // Don't return byte.class, int.class, etc. 
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return baseType.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof BaseTypeSignature && ((BaseTypeSignature) obj).baseType.equals(this.baseType);
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (!(other instanceof BaseTypeSignature)) {
            return false;
        }
        return baseType.equals(((BaseTypeSignature) other).baseType);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return baseType;
    }
}