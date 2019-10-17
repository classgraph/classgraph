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

    /** The type signature character used to represent the base type. */
    private final String typeSignatureChar;

    /** byte type signature. */
    private static final BaseTypeSignature BYTE = new BaseTypeSignature("byte", 'B');

    /** char type signature. */
    private static final BaseTypeSignature CHAR = new BaseTypeSignature("char", 'C');

    /** double type signature. */
    private static final BaseTypeSignature DOUBLE = new BaseTypeSignature("double", 'D');

    /** float type signature. */
    private static final BaseTypeSignature FLOAT = new BaseTypeSignature("float", 'F');

    /** int type signature. */
    private static final BaseTypeSignature INT = new BaseTypeSignature("int", 'I');

    /** long type signature. */
    private static final BaseTypeSignature LONG = new BaseTypeSignature("long", 'J');

    /** short type signature. */
    private static final BaseTypeSignature SHORT = new BaseTypeSignature("short", 'S');

    /** boolean type signature. */
    private static final BaseTypeSignature BOOLEAN = new BaseTypeSignature("boolean", 'Z');

    /** void type signature. */
    static final BaseTypeSignature VOID = new BaseTypeSignature("void", 'V');

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param baseType
     *            the base type
     */
    private BaseTypeSignature(final String baseType, final char typeSignatureChar) {
        super();
        this.baseType = baseType;
        this.typeSignatureChar = Character.toString(typeSignatureChar);
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
     * Get the type signature char used to represent the type, e.g. "Z" for int.
     * 
     * @return the type signature char, as a one-char String.
     */
    public String getTypeSignatureChar() {
        return typeSignatureChar;
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

    /**
     * Get the {@link BaseTypeSignature} for a given type name.
     *
     * @param typeName
     *            the name of the type.
     * @return The {@link BaseTypeSignature} of the named base type, or null if typeName is not a base type.
     */
    public static BaseTypeSignature getTypeSignature(final String typeName) {
        switch (typeName) {
        case "byte":
            return BYTE;
        case "char":
            return CHAR;
        case "double":
            return DOUBLE;
        case "float":
            return FLOAT;
        case "int":
            return INT;
        case "long":
            return LONG;
        case "short":
            return SHORT;
        case "boolean":
            return BOOLEAN;
        case "void":
            return VOID;
        default:
            return null;
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
            return BYTE;
        case 'C':
            parser.next();
            return CHAR;
        case 'D':
            parser.next();
            return DOUBLE;
        case 'F':
            parser.next();
            return FLOAT;
        case 'I':
            parser.next();
            return INT;
        case 'J':
            parser.next();
            return LONG;
        case 'S':
            parser.next();
            return SHORT;
        case 'Z':
            parser.next();
            return BOOLEAN;
        case 'V':
            parser.next();
            return VOID;
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
        return baseType;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        return null;
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        // Don't add byte.class, int.class, etc. 
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
        if (obj == this) {
            return true;
        } else if (!(obj instanceof BaseTypeSignature)) {
            return false;
        }
        return ((BaseTypeSignature) obj).baseType.equals(this.baseType);
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
     * @see io.github.classgraph.TypeSignature#toStringInternal(boolean)
     */
    @Override
    protected String toStringInternal(final boolean useSimpleNames) {
        return baseType;
    }
}