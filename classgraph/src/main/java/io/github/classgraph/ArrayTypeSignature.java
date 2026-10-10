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
public final class ArrayTypeSignature extends ReferenceTypeSignature {
    /**
     * The maximum number of dimensions of an array type (JVMS 4.3.2 and 4.4.1). The nested dimensions of an array
     * type signature are built, hashed and compared by recursion, one call per dimension, so this limit also bounds
     * the depth of that recursion.
     */
    static final int MAX_DIMENSIONS = 255;

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
        var numDims = getNumDimensions();
        var newElementTypeSignature = substitutedElementTypeSignature;
        // The element type of an array type is never itself an array type, so an array type substituted for the
        // element type adds its dimensions to this array's, e.g. E[] with E := String[] is String[][]
        final var substitutedArray = substitutedElementTypeSignature instanceof final ArrayTypeSignature array
                ? array
                : null;
        if (substitutedArray != null) {
            numDims += substitutedArray.getNumDimensions();
            if (numDims > MAX_DIMENSIONS) {
                // There is no array type with this many dimensions, so leave the type variable unsubstituted
                return this;
            }
            newElementTypeSignature = substitutedArray.getElementTypeSignature();
        }
        // The array's own type signature string has to be rebuilt around the new element type
        final var substituted = new ArrayTypeSignature(newElementTypeSignature, numDims,
                "[".repeat(numDims) + TypeSignature.toTypeSignatureStr(newElementTypeSignature));
        // This array's dimensions are the outer dimensions of the new array, and the dimensions of the substituted
        // array, if any, are the inner dimensions
        final var innerDimensions = copyDimensionTypeAnnotations(this, substituted);
        if (substitutedArray != null) {
            copyDimensionTypeAnnotations(substitutedArray, (ArrayTypeSignature) innerDimensions);
        }
        return substituted;
    }

    @Override
    ArrayTypeSignature withTypeAnnotationsPrepended(final List<AnnotationInfo> annotations) {
        final var copy = new ArrayTypeSignature(getElementTypeSignature(), getNumDimensions(), typeSignatureStr);
        copyDimensionTypeAnnotations(this, copy);
        // A type annotation on the array type as a whole is on its outermost dimension
        copy.prependTypeAnnotations(annotations);
        return copy;
    }

    /**
     * Copy the type annotations of each dimension of an array type to the dimension in the same position of another
     * array type, outermost first, which has at least as many dimensions.
     *
     * @param source
     *            the array type to copy the type annotations from.
     * @param destination
     *            the array type to copy the type annotations to, which type variable substitution has just built.
     * @return the nested type of the destination dimension that the innermost dimension of the source was copied
     *         to.
     */
    private static TypeSignature copyDimensionTypeAnnotations(final ArrayTypeSignature source,
            final ArrayTypeSignature destination) {
        var sourceDimension = source;
        var destinationDimension = destination;
        for (;;) {
            destinationDimension.copyTypeAnnotationsFrom(sourceDimension);
            if (!(sourceDimension.nestedType instanceof final ArrayTypeSignature nextSourceDimension)) {
                return destinationDimension.nestedType;
            }
            sourceDimension = nextSourceDimension;
            destinationDimension = (ArrayTypeSignature) destinationDimension.nestedType;
        }
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
     * Get a list of {@link AnnotationInfo} objects for the type annotations on this array type.
     *
     * @see #getNestedType() if you want to read for type annotations on inner (nested) dimensions of the array
     *      type.
     * @return a list of {@link AnnotationInfo} objects for the type annotations on this array type, or the empty
     *         list if none.
     */
    @Override
    public AnnotationInfoList getTypeAnnotationInfo() {
        return super.getTypeAnnotationInfo();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class name of the array, e.g. "java.lang.String[]". This is the name of the erased array class: type
     * annotations and type arguments are not part of it, and an array of a type variable is named after the erasure
     * of the type variable, e.g. "java.lang.Number[]" for {@code T[]} where {@code T extends Number}.
     *
     * @return the class name of the array.
     */
    @Override
    protected String getClassName() {
        var name = className;
        if (name == null) {
            // N.B. build the class name from the element type's class name rather than from toString(), since
            // toString() also renders type annotations and type arguments, which are not part of the class name
            // (and the class name is used as the ArrayClassInfo cache key)
            final var elementTypeSignature = getElementTypeSignature();
            // The class name of a type variable is the name of the class that it is used in, not a class that the
            // type variable can stand for, so name the array after the erasure of the type variable (JLS 4.6)
            final var isTypeVariable = elementTypeSignature instanceof TypeVariableSignature;
            final StringBuilder buf = new StringBuilder(
                    elementTypeSignature instanceof final TypeVariableSignature typeVariableSignature
                            ? typeVariableSignature.getErasedClassName()
                            : elementTypeSignature.getClassName());
            for (int i = 0, numDims = getNumDimensions(); i < numDims; i++) {
                buf.append("[]");
            }
            name = buf.toString();
            // The erasure of a type variable can only be found once the scan result is set, so do not keep the name
            // found before that
            if (!isTypeVariable || scanResult != null) {
                className = name;
            }
        }
        return name;
    }

    @Override
    protected ClassInfo getClassInfo() {
        return getArrayClassInfo();
    }

    /**
     * Make the type signature of an erased array class, which has no type arguments, type annotations or type
     * variables, e.g. "[Ljava/util/List;" for the element class name "java.util.List" and one dimension.
     *
     * @param elementClassName
     *            the class name of the array elements, e.g. "java.util.List", or the name of a primitive type, e.g.
     *            "int".
     * @param numDims
     *            the number of array dimensions.
     * @return the type signature of the erased array class.
     * @throws IllegalArgumentException
     *             if {@code numDims} is greater than {@link #MAX_DIMENSIONS}.
     */
    static ArrayTypeSignature erased(final String elementClassName, final int numDims) {
        if (numDims > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Array type has more than " + MAX_DIMENSIONS + " dimensions");
        }
        final StringBuilder arrayTypeSigStrBuf = new StringBuilder();
        for (var i = 0; i < numDims; i++) {
            arrayTypeSigStrBuf.append('[');
        }
        TypeSignature elementTypeSignature;
        final var baseTypeChar = BaseTypeSignature.getTypeChar(elementClassName);
        if (baseTypeChar != '\0') {
            // Element type is a base (primitive) type
            arrayTypeSigStrBuf.append(baseTypeChar);
            elementTypeSignature = new BaseTypeSignature(baseTypeChar);
        } else {
            // Element type is not a base (primitive) type -- create a type signature for element type
            final var eltTypeSigStr = "L" + elementClassName.replace('.', '/') + ";";
            arrayTypeSigStrBuf.append(eltTypeSigStr);
            // The element type is parsed, so that a nested class name is split into the same parts as in a type
            // signature read from a classfile. A class name that the signature grammar cannot express is referred
            // to by its whole name instead: JVMS 4.2.1 allows '<', '>' and ':' in a class name, but they end an
            // identifier in a type signature.
            ClassRefTypeSignature classRefTypeSignature;
            try {
                final var parser = new TypeSignatureParser(eltTypeSigStr);
                classRefTypeSignature = ClassRefTypeSignature.parse(parser,
                        // No type variables to resolve for generic types
                        /* definingClassName = */ null);
                if (parser.hasMore()) {
                    classRefTypeSignature = null;
                }
            } catch (final TypeSignatureParseException e) {
                classRefTypeSignature = null;
            }
            elementTypeSignature = classRefTypeSignature != null ? classRefTypeSignature
                    : new ClassRefTypeSignature(elementClassName);
        }
        return new ArrayTypeSignature(elementTypeSignature, numDims, arrayTypeSigStrBuf.toString());
    }

    /**
     * Make the type signature of the erased array class of this array type.
     *
     * @return the type signature of the erased array class.
     */
    private ArrayTypeSignature erased() {
        final var numDims = getNumDimensions();
        final var clsName = getClassName();
        return erased(clsName.substring(0, clsName.length() - 2 * numDims), numDims);
    }

    /**
     * Return the {@link ArrayClassInfo} instance for the array class. Every array type with the same class name
     * gets the same {@link ArrayClassInfo} instance, which describes the erased array class, so its type signature
     * has no type arguments or type annotations. Those are only found on this array type signature.
     *
     * @return the {@link ArrayClassInfo} instance.
     */
    public ArrayClassInfo getArrayClassInfo() {
        var classInfo = arrayClassInfo;
        if (classInfo == null) {
            final var scanRes = scanResult;
            if (scanRes != null) {
                final var clsName = getClassName();
                // Use the ArrayClassInfo instance for the same array class if there is one already, so that all
                // array types with the same class name share one. The scan result's own map is only read, not
                // added to, so that looking up an array type does not add a class to the scan result.
                classInfo = (ArrayClassInfo) scanRes.classNameToClassInfo.get(clsName);
                if (classInfo == null) {
                    // The shared instance is made from the erased array type, not from this array type, so that it
                    // does not depend on which array type asked for it first
                    final var newClassInfo = new ArrayClassInfo(erased());
                    newClassInfo.setScanResult(scanRes);
                    classInfo = (ArrayClassInfo) scanRes.classNameToClassInfoMadeAfterScan.putIfAbsent(clsName,
                            newClassInfo);
                    if (classInfo == null) {
                        classInfo = newClassInfo;
                    }
                }
            } else {
                // scanResult is not yet available, create an uncached instance of an ArrayClassInfo for this type
                classInfo = new ArrayClassInfo(erased());
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
        // ArrayClassInfo#setScanResult sets the scan result of its own type signature, which may be this one, so
        // stop once the scan result is set
        if (classInfo != null && classInfo.scanResult != scanResult) {
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
    void findReferencedClassNames(final Set<String> refdClassNames) {
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
        return Objects.equals(this.typeAnnotations, other.typeAnnotations)
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
            final @Nullable List<AnnotationInfo> annotationsToExclude, final StringBuilder buf) {
        // Start with innermost array element type
        getElementTypeSignature().toStringInternal(useSimpleNames, annotationsToExclude, buf);

        // Append array dimensions
        for (var curr = this;;) {
            final var typeAnnotations = curr.typeAnnotations;
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
    void toStringVarArgs(final boolean useSimpleNames, final @Nullable List<AnnotationInfo> annotationsToExclude,
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
        if (numArrayDims > MAX_DIMENSIONS) {
            throw new TypeSignatureParseException(parser,
                    "Array type has more than " + MAX_DIMENSIONS + " dimensions");
        }
        if (numArrayDims > 0) {
            final var elementTypeSignature = TypeSignature.parse(parser, definingClassName);
            if (elementTypeSignature == null) {
                throw new TypeSignatureParseException(parser, "elementTypeSignature == null");
            }
            final var typeSignatureStr = parser.getSubstring(begin, parser.getPosition());
            return new ArrayTypeSignature(elementTypeSignature, numArrayDims, typeSignatureStr);
        } else {
            return null;
        }
    }
}
