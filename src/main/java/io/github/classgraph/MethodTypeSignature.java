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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/** A method type signature (called "MethodSignature" in the classfile documentation). */
public final class MethodTypeSignature extends HierarchicalTypeSignature {
    /** The method type parameters. */
    final List<TypeParameter> typeParameters;

    /** The method parameter type signatures. */
    private final List<TypeSignature> parameterTypeSignatures;

    /** The method result type. */
    private final TypeSignature resultType;

    /** The throws type signatures. */
    private final List<ClassRefOrTypeVariableSignature> throwsSignatures;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param typeParameters
     *            The type parameters for the method.
     * @param paramTypes
     *            The parameter types for the method.
     * @param resultType
     *            The return type for the method.
     * @param throwsSignatures
     *            The throws signatures for the method.
     */
    private MethodTypeSignature(final List<TypeParameter> typeParameters, final List<TypeSignature> paramTypes,
            final TypeSignature resultType, final List<ClassRefOrTypeVariableSignature> throwsSignatures) {
        super();
        this.typeParameters = typeParameters;
        this.parameterTypeSignatures = paramTypes;
        this.resultType = resultType;
        this.throwsSignatures = throwsSignatures;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the type parameters for the method. N.B. this is non-public, since the types have to be aligned with
     * other parameter metadata. The type of a parameter can be obtained post-alignment from the parameter's
     * {@link MethodParameterInfo} object.
     * 
     * @return The type parameters for the method.
     */
    List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    /**
     * Get the type signatures of the method parameters. N.B. this is non-public, since the types have to be aligned
     * with other parameter metadata. The type of a parameter can be obtained post-alignment from the parameter's
     * {@link MethodParameterInfo} object.
     * 
     * @return The parameter types for the method, as {@link TypeSignature} parsed type objects.
     */
    List<TypeSignature> getParameterTypeSignatures() {
        return parameterTypeSignatures;
    }

    /**
     * Get the result type for the method.
     * 
     * @return The result type for the method, as a {@link TypeSignature} parsed type object.
     */
    public TypeSignature getResultType() {
        return resultType;
    }

    /**
     * Get the throws type(s) for the method.
     * 
     * @return The throws types for the method, as {@link TypeSignature} parsed type objects.
     */
    public List<ClassRefOrTypeVariableSignature> getThrowsSignatures() {
        return throwsSignatures;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a method signature.
     * 
     * @param typeDescriptor
     *            The type descriptor of the method.
     * @param definingClassName
     *            The name of the defining class (for resolving type variables).
     * @return The parsed method type signature.
     * @throws ParseException
     *             If method type signature could not be parsed.
     */
    static MethodTypeSignature parse(final String typeDescriptor, final String definingClassName)
            throws ParseException {
        if (typeDescriptor.equals("<init>")) {
            // Special case for instance initialization method signatures in a CONSTANT_NameAndType_info structure:
            // https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.4.2
            return new MethodTypeSignature(Collections.<TypeParameter> emptyList(),
                    Collections.<TypeSignature> emptyList(), BaseTypeSignature.VOID,
                    Collections.<ClassRefOrTypeVariableSignature> emptyList());
        }
        final Parser parser = new Parser(typeDescriptor);
        final List<TypeParameter> typeParameters = TypeParameter.parseList(parser, definingClassName);
        parser.expect('(');
        final List<TypeSignature> paramTypes = new ArrayList<>();
        while (parser.peek() != ')') {
            if (!parser.hasMore()) {
                throw new ParseException(parser, "Ran out of input while parsing method signature");
            }
            final TypeSignature paramType = TypeSignature.parse(parser, definingClassName);
            if (paramType == null) {
                throw new ParseException(parser, "Missing method parameter type signature");
            }
            paramTypes.add(paramType);
        }
        parser.expect(')');
        final TypeSignature resultType = TypeSignature.parse(parser, definingClassName);
        if (resultType == null) {
            throw new ParseException(parser, "Missing method result type signature");
        }
        List<ClassRefOrTypeVariableSignature> throwsSignatures;
        if (parser.peek() == '^') {
            throwsSignatures = new ArrayList<>();
            while (parser.peek() == '^') {
                parser.expect('^');
                final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parser,
                        definingClassName);
                if (classTypeSignature != null) {
                    throwsSignatures.add(classTypeSignature);
                } else {
                    final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parser,
                            definingClassName);
                    if (typeVariableSignature != null) {
                        throwsSignatures.add(typeVariableSignature);
                    } else {
                        throw new ParseException(parser, "Missing type variable signature");
                    }
                }
            }
        } else {
            throwsSignatures = Collections.emptyList();
        }
        if (parser.hasMore()) {
            throw new ParseException(parser, "Extra characters at end of type descriptor");
        }
        final MethodTypeSignature methodSignature = new MethodTypeSignature(typeParameters, paramTypes, resultType,
                throwsSignatures);
        // Add back-links from type variable signature to the method signature it is part of,
        // and to the enclosing class' type signature
        @SuppressWarnings("unchecked")
        final List<TypeVariableSignature> typeVariableSignatures = (List<TypeVariableSignature>) parser.getState();
        if (typeVariableSignatures != null) {
            for (final TypeVariableSignature typeVariableSignature : typeVariableSignatures) {
                typeVariableSignature.containingMethodSignature = methodSignature;
            }
        }
        return methodSignature;
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
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeParameters != null) {
            for (final TypeParameter typeParameter : typeParameters) {
                typeParameter.setScanResult(scanResult);
            }
        }
        if (this.parameterTypeSignatures != null) {
            for (final TypeSignature typeParameter : parameterTypeSignatures) {
                typeParameter.setScanResult(scanResult);
            }
        }
        if (this.resultType != null) {
            this.resultType.setScanResult(scanResult);
        }
        if (throwsSignatures != null) {
            for (final ClassRefOrTypeVariableSignature throwsSignature : throwsSignatures) {
                throwsSignature.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        for (final TypeParameter typeParameter : typeParameters) {
            if (typeParameter != null) {
                typeParameter.findReferencedClassNames(refdClassNames);
            }
        }
        for (final TypeSignature typeSignature : parameterTypeSignatures) {
            if (typeSignature != null) {
                typeSignature.findReferencedClassNames(refdClassNames);
            }
        }
        resultType.findReferencedClassNames(refdClassNames);
        for (final ClassRefOrTypeVariableSignature typeSignature : throwsSignatures) {
            if (typeSignature != null) {
                typeSignature.findReferencedClassNames(refdClassNames);
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        final Set<String> refdClassNames = new HashSet<>();
        findReferencedClassNames(refdClassNames);
        for (final String refdClassName : refdClassNames) {
            final ClassInfo classInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
            classInfo.scanResult = scanResult;
            refdClassInfo.add(classInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return typeParameters.hashCode() + parameterTypeSignatures.hashCode() * 7 + resultType.hashCode() * 15
                + throwsSignatures.hashCode() * 31;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof MethodTypeSignature)) {
            return false;
        }
        final MethodTypeSignature o = (MethodTypeSignature) obj;
        return o.typeParameters.equals(this.typeParameters)
                && o.parameterTypeSignatures.equals(this.parameterTypeSignatures)
                && o.resultType.equals(this.resultType) && o.throwsSignatures.equals(this.throwsSignatures);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        if (!typeParameters.isEmpty()) {
            buf.append('<');
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final String typeParamStr = typeParameters.get(i).toString();
                buf.append(typeParamStr);
            }
            buf.append('>');
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(resultType.toString());

        buf.append(" (");
        for (int i = 0; i < parameterTypeSignatures.size(); i++) {
            if (i > 0) {
                buf.append(", ");
            }
            buf.append(parameterTypeSignatures.get(i).toString());
        }
        buf.append(')');

        if (!throwsSignatures.isEmpty()) {
            buf.append(" throws ");
            for (int i = 0; i < throwsSignatures.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(throwsSignatures.get(i).toString());
            }
        }
        return buf.toString();
    }
}