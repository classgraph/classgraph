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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.Parser;

/** A type signature for a base type (byte, char, double, float, int, long, short, boolean, or void). */
public class BaseTypeSignature extends TypeSignature {
    /** The type signature character used to represent the base type. */
    private final char typeSignatureChar;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    BaseTypeSignature(final char typeSignatureChar) {
        super();
        switch (typeSignatureChar) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
        case 'V':
            this.typeSignatureChar = typeSignatureChar;
            break;
        default:
            throw new IllegalArgumentException(
                    "Illegal " + BaseTypeSignature.class.getSimpleName() + " type: '" + typeSignatureChar + "'");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the type as a string.
     *
     * @param typeChar
     *            the type character, e.g. 'I'.
     * @return The name of the type, e.g. "int", or null if there was no match.
     */
    static String getTypeStr(final char typeChar) {
        switch (typeChar) {
        case 'B':
            return "byte";
        case 'C':
            return "char";
        case 'D':
            return "double";
        case 'F':
            return "float";
        case 'I':
            return "int";
        case 'J':
            return "long";
        case 'S':
            return "short";
        case 'Z':
            return "boolean";
        case 'V':
            return "void";
        default:
            return null;
        }
    }

    /**
     * Get the name of the type as a string.
     *
     * @param typeStr
     *            the type character, e.g. "int".
     * @return The type, character, e.g. 'I', or '\0' if there was no match.
     */
    static char getTypeChar(final String typeStr) {
        switch (typeStr) {
        case "byte":
            return 'B';
        case "char":
            return 'C';
        case "double":
            return 'D';
        case "float":
            return 'F';
        case "int":
            return 'I';
        case "long":
            return 'J';
        case "short":
            return 'S';
        case "boolean":
            return 'Z';
        case "void":
            return 'V';
        default:
            return '\0';
        }
    }

    /**
     * Get the type for a type character.
     *
     * @param typeChar
     *            the type character, e.g. 'I'.
     * @return The type class, e.g. int.class, or null if there was no match.
     */
    static Class<?> getType(final char typeChar) {
        switch (typeChar) {
        case 'B':
            return byte.class;
        case 'C':
            return char.class;
        case 'D':
            return double.class;
        case 'F':
            return float.class;
        case 'I':
            return int.class;
        case 'J':
            return long.class;
        case 'S':
            return short.class;
        case 'Z':
            return boolean.class;
        case 'V':
            return void.class;
        default:
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the type signature char used to represent the type, e.g. 'I' for int.
     * 
     * @return the type signature char, as a one-char String.
     */
    public char getTypeSignatureChar() {
        return typeSignatureChar;
    }

    /**
     * Get the name of the type as a string.
     *
     * @return The name of the type, such as "int", "float", or "void".
     */
    public String getTypeStr() {
        return getTypeStr(typeSignatureChar);
    }

    /**
     * Get the type.
     *
     * @return The class of the base type, such as int.class, float.class, or void.class.
     */
    public Class<?> getType() {
        return getType(typeSignatureChar);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        addTypeAnnotation(annotationInfo);
    }

    // -------------------------------------------------------------------------------------------------------------

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
            throw new IllegalArgumentException("Primitive class " + getTypeStr() + " cannot be cast to "
                    + superclassOrInterfaceType.getName());
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
            return new BaseTypeSignature('B');
        case 'C':
            parser.next();
            return new BaseTypeSignature('C');
        case 'D':
            parser.next();
            return new BaseTypeSignature('D');
        case 'F':
            parser.next();
            return new BaseTypeSignature('F');
        case 'I':
            parser.next();
            return new BaseTypeSignature('I');
        case 'J':
            parser.next();
            return new BaseTypeSignature('J');
        case 'S':
            parser.next();
            return new BaseTypeSignature('S');
        case 'Z':
            parser.next();
            return new BaseTypeSignature('Z');
        case 'V':
            parser.next();
            return new BaseTypeSignature('V');
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
        return getTypeStr();
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

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        // Don't set ScanResult for BaseTypeSignature objects (#419).
        // The ScanResult is not needed, since this class does not classload through the ScanResult.
        // Also, specific instances of BaseTypeSignature for each primitive type are assigned to static fields
        // in this class, which are shared across all usages of this class, so they should not contain any
        // values that are specific to a given ScanResult. Setting the ScanResult from different scan processes
        // would cause the scanResult field to only reflect the result of the most recent scan, and the reference
        // to that scan would prevent garbage collection.
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return typeSignatureChar;
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
        final BaseTypeSignature other = (BaseTypeSignature) obj;
        return Objects.equals(this.typeAnnotationInfo, other.typeAnnotationInfo)
                && other.typeSignatureChar == this.typeSignatureChar;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (!(other instanceof BaseTypeSignature)) {
            return false;
        }
        return typeSignatureChar == ((BaseTypeSignature) other).typeSignatureChar;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        if (typeAnnotationInfo != null) {
            for (final AnnotationInfo annotationInfo : typeAnnotationInfo) {
                if (annotationsToExclude == null || !annotationsToExclude.contains(annotationInfo)) {
                    annotationInfo.toString(useSimpleNames, buf);
                    buf.append(' ');
                }
            }
        }
        buf.append(getTypeStr());
    }
}