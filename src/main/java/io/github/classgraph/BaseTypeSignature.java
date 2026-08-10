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
import org.jspecify.annotations.Nullable;

/**
 * A type signature for a base type (byte, char, double, float, int, long, short, boolean, or void). This
 * corresponds to the {@code BaseType} production of the signature grammar in section 4.7.9.1 of the JVM
 * Specification.
 */
public class BaseTypeSignature extends TypeSignature {
    /** The type signature character used to represent the base type. */
    private final char typeSignatureChar;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param typeSignatureChar
     *            the type signature character used to represent the base type, e.g. 'I' for int.
     */
    BaseTypeSignature(final char typeSignatureChar) {
        super();
        switch (typeSignatureChar) {
        case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> this.typeSignatureChar = typeSignatureChar;
        default -> throw new IllegalArgumentException(
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
    static @Nullable String typeCharToString(final char typeChar) {
        return switch (typeChar) {
        case 'B' -> "byte";
        case 'C' -> "char";
        case 'D' -> "double";
        case 'F' -> "float";
        case 'I' -> "int";
        case 'J' -> "long";
        case 'S' -> "short";
        case 'Z' -> "boolean";
        case 'V' -> "void";
        default -> null;
        };
    }

    /**
     * Get the name of the type as a string.
     *
     * @param typeStr
     *            the type character, e.g. "int".
     * @return The type, character, e.g. 'I', or '\0' if there was no match.
     */
    static char getTypeChar(final String typeStr) {
        return switch (typeStr) {
        case "byte" -> 'B';
        case "char" -> 'C';
        case "double" -> 'D';
        case "float" -> 'F';
        case "int" -> 'I';
        case "long" -> 'J';
        case "short" -> 'S';
        case "boolean" -> 'Z';
        case "void" -> 'V';
        default -> '\0';
        };
    }

    /**
     * Get the type for a type character.
     *
     * @param typeChar
     *            the type character, e.g. 'I'.
     * @return The type class, e.g. int.class, or null if there was no match.
     */
    static @Nullable Class<?> getType(final char typeChar) {
        return switch (typeChar) {
        case 'B' -> byte.class;
        case 'C' -> char.class;
        case 'D' -> double.class;
        case 'F' -> float.class;
        case 'I' -> int.class;
        case 'J' -> long.class;
        case 'S' -> short.class;
        case 'Z' -> boolean.class;
        case 'V' -> void.class;
        default -> null;
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the type signature char used to represent the type, e.g. 'I' for int.
     *
     * @return the type signature char.
     */
    public char getTypeSignatureChar() {
        return typeSignatureChar;
    }

    /**
     * Get the name of the type as a string.
     *
     * @return The name of the type, such as "int", "float", or "void".
     */
    public String getTypeString() {
        return Objects.requireNonNull(typeCharToString(typeSignatureChar));
    }

    /**
     * Get the type.
     *
     * @return The class of the base type, such as int.class, float.class, or void.class.
     */
    public Class<?> getType() {
        return Objects.requireNonNull(getType(typeSignatureChar));
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        addTypeAnnotation(annotationInfo);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a base type.
     *
     * @param parser
     *            the parser
     * @return the base type signature
     */
    static @Nullable BaseTypeSignature parse(final Parser parser) {
        final var typeSignatureChar = parser.peek();
        return switch (typeSignatureChar) {
        case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> {
            parser.next();
            yield new BaseTypeSignature(typeSignatureChar);
        }
        default -> null;
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        return getTypeString();
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected @Nullable ClassInfo getClassInfo() {
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

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#setScanResult(ScanResult)
     */
    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        // Don't set ScanResult for BaseTypeSignature objects (#419). The ScanResult is not needed, since this class
        // does not classload through the ScanResult, and holding a reference to the ScanResult would prevent it
        // from being garbage collected.
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return typeSignatureChar;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final BaseTypeSignature other)) {
            return false;
        }
        return Objects.equals(this.typeAnnotationInfo, other.typeAnnotationInfo)
                && other.typeSignatureChar == this.typeSignatureChar;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.
     * classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final @Nullable TypeSignature other) {
        if (!(other instanceof final BaseTypeSignature otherBaseTypeSignature)) {
            return false;
        }
        return typeSignatureChar == otherBaseTypeSignature.typeSignatureChar;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable AnnotationInfoList annotationsToExclude, final StringBuilder buf) {
        if (typeAnnotationInfo != null) {
            for (final AnnotationInfo annotationInfo : typeAnnotationInfo) {
                if (annotationsToExclude == null || !annotationsToExclude.contains(annotationInfo)) {
                    annotationInfo.toString(useSimpleNames, buf);
                    buf.append(' ');
                }
            }
        }
        buf.append(getTypeString());
    }
}