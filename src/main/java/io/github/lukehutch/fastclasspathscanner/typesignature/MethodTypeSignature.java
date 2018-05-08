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
package io.github.lukehutch.fastclasspathscanner.typesignature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseException;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseState;

/** A method type signature (called "MethodSignature" in the classfile documentation). */
public class MethodTypeSignature extends HierarchicalTypeSignature {
    /** The method type parameters. */
    final List<TypeParameter> typeParameters;

    /** The method parameter type signatures. */
    private final List<TypeSignature> parameterTypeSignatures;

    /** The method result type. */
    private final TypeSignature resultType;

    /** The throws type signatures. */
    private final List<ClassRefOrTypeVariableSignature> throwsSignatures;

    /**
     * @param typeParameters
     *            The type parameters for the method.
     * @param paramTypes
     *            The parameter types for the method.
     * @param resultType
     *            The result type for the method.
     * @param throwsSignatures
     *            The throws signatures for the method.
     */
    public MethodTypeSignature(final List<TypeParameter> typeParameters, final List<TypeSignature> paramTypes,
            final TypeSignature resultType, final List<ClassRefOrTypeVariableSignature> throwsSignatures) {
        this.typeParameters = typeParameters;
        this.parameterTypeSignatures = paramTypes;
        this.resultType = resultType;
        this.throwsSignatures = throwsSignatures;
    }

    /**
     * Get the type parameters for the method.
     * 
     * @return The type parameters for the method.
     */
    public List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    /**
     * Get the type signatures of the method parameters.
     * 
     * @return The parameter types for the method, as {@link TypeSignature} parsed type objects.
     */
    public List<TypeSignature> getParameterTypeSignatures() {
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

    @Override
    public void getAllReferencedClassNames(final Set<String> classNameListOut) {
        for (final TypeParameter typeParameter : typeParameters) {
            if (typeParameter != null) {
                typeParameter.getAllReferencedClassNames(classNameListOut);
            }
        }
        for (final TypeSignature typeSignature : parameterTypeSignatures) {
            if (typeSignature != null) {
                typeSignature.getAllReferencedClassNames(classNameListOut);
            }
        }
        resultType.getAllReferencedClassNames(classNameListOut);
        for (final ClassRefOrTypeVariableSignature typeSignature : throwsSignatures) {
            if (typeSignature != null) {
                typeSignature.getAllReferencedClassNames(classNameListOut);
            }
        }
    }

    @Override
    public int hashCode() {
        return typeParameters.hashCode() + parameterTypeSignatures.hashCode() * 7 + resultType.hashCode() * 15
                + throwsSignatures.hashCode() * 31;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof MethodTypeSignature)) {
            return false;
        }
        final MethodTypeSignature o = (MethodTypeSignature) obj;
        return o.typeParameters.equals(this.typeParameters)
                && o.parameterTypeSignatures.equals(this.parameterTypeSignatures)
                && o.resultType.equals(this.resultType) && o.throwsSignatures.equals(this.throwsSignatures);
    }

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

    /**
     * Parse a method signature (ignores class context, i.e. no ClassInfo needs to be provided -- this means that
     * type variables cannot be resolved to the matching type parameter).
     * 
     * @param typeDescriptor
     *            The low-level internal method type descriptor or type signature to parse.
     * @return The parsed type signature of the method.
     */
    public static MethodTypeSignature parse(final String typeDescriptor) {
        return MethodTypeSignature.parse(/* classInfo = */ null, typeDescriptor);
    }

    /**
     * Parse a method signature.
     * 
     * @param classInfo
     *            The {@link ClassInfo} of the containing class.
     * @param typeDescriptor
     *            The type descriptor of the method.
     * @return The parsed method type signature.
     */
    public static MethodTypeSignature parse(final ClassInfo classInfo, final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        try {
            final List<TypeParameter> typeParameters = TypeParameter.parseList(parseState);
            parseState.expect('(');
            final List<TypeSignature> paramTypes = new ArrayList<>();
            while (parseState.peek() != ')') {
                if (!parseState.hasMore()) {
                    throw new ParseException();
                }
                final TypeSignature paramType = TypeSignature.parse(parseState);
                if (paramType == null) {
                    throw new ParseException();
                }
                paramTypes.add(paramType);
            }
            parseState.expect(')');
            final TypeSignature resultType = TypeSignature.parse(parseState);
            if (resultType == null) {
                throw new ParseException();
            }
            List<ClassRefOrTypeVariableSignature> throwsSignatures;
            if (parseState.peek() == '^') {
                throwsSignatures = new ArrayList<>();
                while (parseState.peek() == '^') {
                    parseState.expect('^');
                    final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parseState);
                    if (classTypeSignature != null) {
                        throwsSignatures.add(classTypeSignature);
                    } else {
                        final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parseState);
                        if (typeVariableSignature != null) {
                            throwsSignatures.add(typeVariableSignature);
                        } else {
                            throw new ParseException();
                        }
                    }
                }
            } else {
                throwsSignatures = Collections.emptyList();
            }
            if (parseState.hasMore()) {
                throw new IllegalArgumentException("Extra characters at end of type descriptor: " + parseState);
            }
            final MethodTypeSignature methodSignature = new MethodTypeSignature(typeParameters, paramTypes,
                    resultType, throwsSignatures);
            // Add back-links from type variable signature to the method signature it is part of,
            // and to the enclosing class' type signature
            for (final TypeVariableSignature typeVariableSignature : parseState.getTypeVariableSignatures()) {
                typeVariableSignature.containingMethodSignature = methodSignature;
            }
            if (classInfo != null) {
                final ClassTypeSignature classSignature = classInfo.getTypeSignature();
                for (final TypeVariableSignature typeVariableSignature : parseState.getTypeVariableSignatures()) {
                    typeVariableSignature.containingClassSignature = classSignature;
                }
            }
            return methodSignature;
        } catch (final Exception e) {
            throw new IllegalArgumentException("Type signature could not be parsed: " + parseState, e);
        }
    }
}