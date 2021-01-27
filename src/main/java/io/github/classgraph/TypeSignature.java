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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A type signature for a reference type or base type. Subclasses are {@link ReferenceTypeSignature} (whose own
 * subclasses are {@link ClassRefTypeSignature}, {@link TypeVariableSignature}, and {@link ArrayTypeSignature}), and
 * {@link BaseTypeSignature}.
 */
public abstract class TypeSignature extends HierarchicalTypeSignature {
    /** Constructor. */
    protected TypeSignature() {
        // Empty
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        final String className = getClassName();
        if (className != null && !className.isEmpty()) {
            refdClassNames.add(getClassName());
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info.
     */
    @Override
    final protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final LogNode log) {
        final Set<String> refdClassNames = new HashSet<>();
        findReferencedClassNames(refdClassNames);
        for (final String refdClassName : refdClassNames) {
            final ClassInfo classInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
            classInfo.scanResult = scanResult;
            refdClassInfo.add(classInfo);
        }
    }

    /**
     * Get a list of {@link AnnotationInfo} objects for any type annotations on this type, or null if none.
     * 
     * @return a list of {@link AnnotationInfo} objects for any type annotations on this type, or null if none.
     */
    public AnnotationInfoList getTypeAnnotationInfo() {
        return typeAnnotationInfo;
    }

    /**
     * Compare base types, ignoring generic type parameters.
     * 
     * @param other
     *            the other {@link TypeSignature} to compare to.
     * @return True if the two {@link TypeSignature} objects are equal, ignoring type parameters.
     */
    public abstract boolean equalsIgnoringTypeParams(final TypeSignature other);

    /**
     * Parse a type signature.
     * 
     * @param parser
     *            The parser
     * @param definingClass
     *            The class containing the type descriptor.
     * @return The parsed type descriptor or type signature.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static TypeSignature parse(final Parser parser, final String definingClass) throws ParseException {
        final ReferenceTypeSignature referenceTypeSignature = ReferenceTypeSignature
                .parseReferenceTypeSignature(parser, definingClass);
        if (referenceTypeSignature != null) {
            return referenceTypeSignature;
        }
        final BaseTypeSignature baseTypeSignature = BaseTypeSignature.parse(parser);
        if (baseTypeSignature != null) {
            return baseTypeSignature;
        }
        return null;
    }

    /**
     * Parse a type signature.
     * 
     * @param typeDescriptor
     *            The type descriptor or type signature to parse.
     * @param definingClass
     *            The class containing the type descriptor.
     * @return The parsed type descriptor or type signature.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static TypeSignature parse(final String typeDescriptor, final String definingClass) throws ParseException {
        final Parser parser = new Parser(typeDescriptor);
        TypeSignature typeSignature;
        typeSignature = parse(parser, definingClass);
        if (typeSignature == null) {
            throw new ParseException(parser, "Could not parse type signature");
        }
        if (parser.hasMore()) {
            throw new ParseException(parser, "Extra characters at end of type descriptor");
        }
        return typeSignature;
    }

    /**
     * Add a type annotation to this type.
     * 
     * @param typePath
     *            The type path.
     * @param annotationInfo
     *            The annotation to add.
     */
    @Override
    protected abstract void addTypeAnnotation(List<TypePathNode> typePath, AnnotationInfo annotationInfo);
}