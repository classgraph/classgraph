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
 * Copyright (c) 2026 Luke Hutchison
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import org.jspecify.annotations.Nullable;

/**
 * An array type signature, i.e. an element type with one or more array dimensions. This corresponds to the
 * {@code ArrayTypeSignature} production of the signature grammar in section 4.7.9.1 of the JVM Specification.
 */
public class ArrayTypeSignature extends ReferenceTypeSignature {
    /** The raw type signature string for the array type. */
    private final String typeSignatureStr;

    /** Human-readable class name, e.g. "java.lang.String[]". */
    private @Nullable String className;

    /** Array class info. */
    private @Nullable ArrayClassInfo arrayClassInfo;

    /**
     * The nested type (another {@link ArrayTypeSignature}, or the base element type).
     */
    private final TypeSignature nestedType;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param elementTypeSignature
     *            The type signature of the array elements.
     * @param numDims
     *            The number of array dimensions.
     * @param typeSignatureStr
     *            Raw array type signature string (e.g. "[[I")
     */
    ArrayTypeSignature(final TypeSignature elementTypeSignature, final int numDims, final String typeSignatureStr) {
        super();
        final var typeSigHasTwoOrMoreDims = typeSignatureStr.startsWith("[[");
        if (numDims < 1) {
            throw new IllegalArgumentException("numDims < 1");
        } else if ((numDims >= 2) != typeSigHasTwoOrMoreDims) {
            throw new IllegalArgumentException("numDims does not match type signature");
        }
        this.typeSignatureStr = typeSignatureStr;
        this.nestedType = typeSigHasTwoOrMoreDims
                // Strip one array dimension for nested type
                ? new ArrayTypeSignature(elementTypeSignature, numDims - 1, typeSignatureStr.substring(1))
                // Nested type for innermost dimension is element type
                : elementTypeSignature;
    }

    /**
     * Get the raw array type signature string, e.g. "[[I".
     *
     * @return the raw array type signature string.
     */
    public String getTypeSignatureString() {
        return typeSignatureStr;
    }

    /**
     * Get the type signature of the innermost element type of the array.
     *
     * @return The type signature of the innermost element type.
     */
    public TypeSignature getElementTypeSignature() {
        var curr = this;
        while (curr.nestedType instanceof final ArrayTypeSignature nested) {
            curr = nested;
        }
        return curr.getNestedType();
    }

    /**
     * Get the number of dimensions of the array.
     *
     * @return The number of dimensions of the array.
     */
    public int getNumDimensions() {
        var numDims = 1;
        var curr = this;
        while (curr.nestedType instanceof final ArrayTypeSignature nested) {
            curr = nested;
            numDims++;
        }
        return numDims;
    }

    @Override
    TypeSignature substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        final var elementTypeSignature = getElementTypeSignature();
        final var substitutedElementTypeSignature = elementTypeSignature.substituteTypeVariables(substitutions);
        if (substitutedElementTypeSignature == elementTypeSignature) {
            return this;
        }
        // The array's own type signature string has to be rebuilt around the substituted element type
        final var numDims = getNumDimensions();
        final StringBuilder buf = new StringBuilder();
        for (var i = 0; i < numDims; i++) {
            buf.append('[');
        }
        buf.append(TypeSignature.toTypeSignatureStr(substitutedElementTypeSignature));
        return new ArrayTypeSignature(substitutedElementTypeSignature, numDims, buf.toString());
    }

    /**
     * Get the nested type, which is another {@link ArrayTypeSignature} with one dimension fewer, if this array has
     * 2 or more dimensions, otherwise this returns the element type.
     *
     * @return The nested type.
     */
    public TypeSignature getNestedType() {
        return nestedType;
    }

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        if (typePath.isEmpty()) {
            addTypeAnnotation(annotationInfo);
        } else {
            final var head = typePath.get(0);
            if (head.typePathKind() != 0 || head.typeArgumentIdx() != 0) {
                throw new IllegalArgumentException("typePath element contains bad values: " + head);
            }
            nestedType.addTypeAnnotation(typePath.subList(1, typePath.size()), annotationInfo);
        }
    }

    /**
     * Get a list of {@link AnnotationInfo} objects for the type annotations on this array type, or null if none.
     *
     * @see #getNestedType() if you want to read for type annotations on inner (nested) dimensions of the array
     *      type.
     * @return a list of {@link AnnotationInfo} objects for the type annotations of on this array type, or null if
     *         none.
     */
    @Override
    public @Nullable AnnotationInfoList getTypeAnnotationInfo() {
        return super.getTypeAnnotationInfo();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        var name = className;
        if (name == null) {
            // N.B. build the class name from the element type's class name rather than from toString(), since
            // toString() also renders type annotations and type arguments, which are not part of the class name
            // (and the class name is used both as the ArrayClassInfo cache key and as the name to classload by)
            final StringBuilder buf = new StringBuilder(getElementTypeSignature().getClassName());
            for (int i = 0, numDims = getNumDimensions(); i < numDims; i++) {
                buf.append("[]");
            }
            className = name = buf.toString();
        }
        return name;
    }

    @Override
    protected ClassInfo getClassInfo() {
        return getArrayClassInfo();
    }

    /**
     * Return an {@link ArrayClassInfo} instance for the array class, cast to its superclass.
     *
     * @return the {@link ArrayClassInfo} instance.
     */
    public ArrayClassInfo getArrayClassInfo() {
        var classInfo = arrayClassInfo;
        if (classInfo == null) {
            final var scanRes = scanResult;
            if (scanRes != null) {
                final var clsName = getClassName();
                // Cache ArrayClassInfo instances using scanResult.classNameToClassInfo, if scanResult is available
                classInfo = (ArrayClassInfo) scanRes.classNameToClassInfo.get(clsName);
                if (classInfo == null) {
                    scanRes.classNameToClassInfo.put(clsName, classInfo = new ArrayClassInfo(this));
                    classInfo.setScanResult(scanRes);
                }
            } else {
                // scanResult is not yet available, create an uncached instance of an ArrayClassInfo for this type
                classInfo = new ArrayClassInfo(this);
            }
            arrayClassInfo = classInfo;
        }
        return classInfo;
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        nestedType.setScanResult(scanResult);
        final var classInfo = arrayClassInfo;
        if (classInfo != null) {
            classInfo.setScanResult(scanResult);
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        nestedType.findReferencedClassNames(refdClassNames);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return 1 + nestedType.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ArrayTypeSignature other)) {
            return false;
        }
        return Objects.equals(this.typeAnnotationInfo, other.typeAnnotationInfo)
                && this.nestedType.equals(other.nestedType);
    }

    @Override
    public boolean equalsIgnoringTypeParams(final @Nullable TypeSignature other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final ArrayTypeSignature o)) {
            return false;
        }
        return this.nestedType.equalsIgnoringTypeParams(o.nestedType);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable AnnotationInfoList annotationsToExclude, final StringBuilder buf) {
        // Start with innermost array element type
        getElementTypeSignature().toStringInternal(useSimpleNames, annotationsToExclude, buf);

        // Append array dimensions
        for (var curr = this;;) {
            final var typeAnnotations = curr.typeAnnotationInfo;
            if (typeAnnotations != null && !typeAnnotations.isEmpty()) {
                for (final AnnotationInfo annotationInfo : typeAnnotations) {
                    if (buf.isEmpty() || buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    annotationInfo.toString(useSimpleNames, buf);
                }
                buf.append(' ');
            }

            buf.append("[]");

            if (curr.nestedType instanceof final ArrayTypeSignature nested) {
                curr = nested;
            } else {
                break;
            }
        }
    }

    /**
     * Render this array type as the type of a variadic method parameter, i.e. with the final {@code "[]"} replaced
     * with {@code "..."}.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param annotationsToExclude
     *            the annotations not to show at the toplevel of the type, or null to show all annotations
     * @param buf
     *            the buffer to append to
     */
    void toStringVarArgs(final boolean useSimpleNames, final @Nullable AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        // The rendering of an array type always ends in "[]", since an array has at least one dimension
        toStringInternal(useSimpleNames, annotationsToExclude, buf);
        buf.setLength(buf.length() - 2);
        buf.append("...");
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parses the array type signature.
     *
     * @param parser
     *            the parser
     * @param definingClassName
     *            the defining class name
     * @return the array type signature
     * @throws TypeSignatureParseException
     *             if parsing fails
     */
    static @Nullable ArrayTypeSignature parse(final TypeSignatureParser parser,
            final @Nullable String definingClassName) throws TypeSignatureParseException {
        var numArrayDims = 0;
        final var begin = parser.getPosition();
        while (parser.peek() == '[') {
            numArrayDims++;
            parser.next();
        }
        if (numArrayDims > 0) {
            final var elementTypeSignature = TypeSignature.parse(parser, definingClassName);
            if (elementTypeSignature == null) {
                throw new TypeSignatureParseException(parser, "elementTypeSignature == null");
            }
            final var typeSignatureStr = parser.getSubsequence(begin, parser.getPosition()).toString();
            return new ArrayTypeSignature(elementTypeSignature, numArrayDims, typeSignatureStr);
        } else {
            return null;
        }
    }
}
