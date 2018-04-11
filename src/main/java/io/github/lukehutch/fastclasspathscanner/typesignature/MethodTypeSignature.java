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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseException;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseState;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;

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

    public MethodTypeSignature(final List<TypeParameter> typeParameters, final List<TypeSignature> paramTypes,
            final TypeSignature resultType, final List<ClassRefOrTypeVariableSignature> throwsSignatures) {
        this.typeParameters = typeParameters;
        this.parameterTypeSignatures = paramTypes;
        this.resultType = resultType;
        this.throwsSignatures = throwsSignatures;
    }

    /** Get the type parameters for the method. */
    public List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    /** Get the type signatures of the method parameters. */
    public List<TypeSignature> getParameterTypeSignatures() {
        return parameterTypeSignatures;
    }

    /** Get the result type for the method. */
    public TypeSignature getResultType() {
        return resultType;
    }

    /** Get the throws type(s) for the method. */
    public List<ClassRefOrTypeVariableSignature> getThrowsSignatures() {
        return throwsSignatures;
    }

    @Override
    public void getAllReferencedClassNames(final Set<String> classNameListOut) {
        for (final TypeParameter typeParameter : typeParameters) {
            if(typeParameter != null)
                typeParameter.getAllReferencedClassNames(classNameListOut);
        }
        for (final TypeSignature typeSignature : parameterTypeSignatures) {
            if(typeSignature != null)
                typeSignature.getAllReferencedClassNames(classNameListOut);
        }
        resultType.getAllReferencedClassNames(classNameListOut);
        for (final ClassRefOrTypeVariableSignature typeSignature : throwsSignatures) {
            if(typeSignature != null)
                typeSignature.getAllReferencedClassNames(classNameListOut);
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

    /**
     * Get a string representation of the method. Note that constructors are named {@code "<init>"}, and private
     * static class initializer blocks are named {@code "<clinit>"}.
     */
    public String toString(final List<AnnotationInfo> annotationInfo, final int modifiers,
            final boolean isConstructor, final String methodName, final boolean isVarArgs,
            final String[] parameterNames, final int[] parameterAccessFlags,
            final AnnotationInfo[][] parameterAnnotationInfo) {

        if ((parameterNames != null && parameterTypeSignatures.size() != parameterNames.length)
                || (parameterAccessFlags != null && parameterTypeSignatures.size() != parameterAccessFlags.length)
                || (parameterAnnotationInfo != null
                        && parameterTypeSignatures.size() != parameterAnnotationInfo.length)) {
            // Should not happen
            throw new RuntimeException("Parameter number mismatch");
        }

        final StringBuilder buf = new StringBuilder();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(annotation.toString());
            }
        }

        if (modifiers != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, /* isMethod = */ true, buf);
        }

        if (!typeParameters.isEmpty()) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append('<');
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(typeParameters.get(i).toString());
            }
            buf.append(">");
        }

        if (!isConstructor) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(resultType.toString());
        }

        buf.append(' ');
        if (methodName != null) {
            buf.append(methodName);
        }

        buf.append('(');
        for (int i = 0; i < parameterTypeSignatures.size(); i++) {
            if (i > 0) {
                buf.append(", ");
            }
            if (parameterAnnotationInfo != null) {
                final AnnotationInfo[] annotationInfoForParameter = parameterAnnotationInfo[i];
                for (int j = 0; j < annotationInfoForParameter.length; j++) {
                    buf.append(annotationInfoForParameter[j].toString());
                    buf.append(' ');
                }
            }
            if (parameterAccessFlags != null) {
                final int flag = parameterAccessFlags[i];
                if ((flag & Modifier.FINAL) != 0) {
                    buf.append("final ");
                }
                if ((flag & TypeUtils.MODIFIER_SYNTHETIC) != 0) {
                    buf.append("synthetic ");
                }
                if ((flag & TypeUtils.MODIFIER_MANDATED) != 0) {
                    buf.append("mandated ");
                }
            }

            final TypeSignature paramType = parameterTypeSignatures.get(i);
            if (isVarArgs && (i == parameterTypeSignatures.size() - 1)) {
                // Show varargs params correctly
                if (!(paramType instanceof ArrayTypeSignature)) {
                    throw new IllegalArgumentException(
                            "Got non-array type for last parameter of varargs method " + methodName);
                }
                final ArrayTypeSignature arrayType = (ArrayTypeSignature) paramType;
                if (arrayType.numArrayDims == 0) {
                    throw new IllegalArgumentException(
                            "Got a zero-dimension array type for last parameter of varargs method " + methodName);
                }
                // Replace last "[]" with "..."
                buf.append(new ArrayTypeSignature(arrayType.elementTypeSignature, arrayType.numArrayDims - 1)
                        .toString());
                buf.append("...");
            } else {
                buf.append(paramType.toString());
            }
            if (parameterNames != null) {
                final String paramName = parameterNames[i];
                buf.append(' ');
                buf.append(paramName == null ? "_unnamed_param_" + i : paramName);
            }
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

    @Override
    public String toString() {
        return toString(/* annotationInfo = */ null, /* modifiers = */ 0, /* isConstructor = */ false,
                /* methodName = */ null, /* isVarArgs = */ false, /* parameterNames = */ null,
                /* parameterAccessFlags = */ null, /* parameterAnnotationInfo = */ null);
    }

    /**
     * Merge together programmer-view and JDK-internal method type signatures.
     * 
     * @param methodTypeSignature
     *            The programmer-view type signature, with type parameters where possible, and without synthetic
     *            parameters.
     * @param methodTypeSignatureInternal
     *            The JDK-internal type signature, without type parameters, but including synthetic parameters, if
     *            any.
     * @param parameterAccessFlags
     *            The parameter modifiers for parameters in the JDK-internal type signature.
     * @return A MethodSignature consisting of all information from both type signatures.
     */
    public static MethodTypeSignature merge(final MethodTypeSignature methodTypeSignature,
            final MethodTypeSignature methodTypeSignatureInternal, final int[] parameterAccessFlagsInternal) {
        if (methodTypeSignature == null || methodTypeSignatureInternal == null) {
            throw new IllegalArgumentException("Signatures must be non-null");
        }
        if (!methodTypeSignatureInternal.typeParameters.isEmpty()) {
            throw new IllegalArgumentException("typeSignatureInternal.typeParameters should be empty");
        }
        if (!methodTypeSignatureInternal.resultType.equalsIgnoringTypeParams(methodTypeSignature.resultType)) {
            throw new IllegalArgumentException("Result types could not be reconciled: "
                    + methodTypeSignatureInternal.resultType + " vs. " + methodTypeSignature.resultType);
        }
        // parameterAccessFlags is only available in classfiles compiled in JDK8 or above using
        // the -parameters commandline switch, or code compiled with Kotlin or some other language
        if (parameterAccessFlagsInternal != null
                && parameterAccessFlagsInternal.length != methodTypeSignatureInternal.parameterTypeSignatures
                        .size()) {
            throw new IllegalArgumentException(
                    "Parameter arity mismatch between access flags and internal param types");
        }
        List<TypeSignature> mergedParamTypes;
        if (parameterAccessFlagsInternal == null) {
            // If there are no parameter access flags, there must be no difference in the number
            // of parameters between the JDK-internal and programmer-visible type signature
            // (i.e. if there are synthetic parameters, then the classfile should specify
            // this by adding the parameter modifier flags section to the method attributes).
            // It's possible this is not always true, so if this exception is thrown, please
            // report a bug in the GitHub bug tracker.
            if (methodTypeSignature.parameterTypeSignatures
                    .size() != methodTypeSignatureInternal.parameterTypeSignatures.size()) {
                throw new IllegalArgumentException("Unexpected mismatch in method paramTypes arity");
            }
            // Use the programmer-visible paramTypes, since these will have type info if it is available
            mergedParamTypes = methodTypeSignature.parameterTypeSignatures;
        } else {
            mergedParamTypes = new ArrayList<>(methodTypeSignatureInternal.parameterTypeSignatures.size());
            int internalParamIdx = 0;
            int paramIdx = 0;
            for (; internalParamIdx < methodTypeSignatureInternal.parameterTypeSignatures
                    .size(); internalParamIdx++) {
                if ((parameterAccessFlagsInternal[internalParamIdx]
                        & (TypeUtils.MODIFIER_SYNTHETIC | TypeUtils.MODIFIER_MANDATED)) != 0) {
                    // This parameter is present in JDK-internal type signature, but not in the 
                    // programmer-visible signature. This should only be true for synthetic
                    // parameters, and they should not have any type parameters, due to type
                    // erasure.
                    mergedParamTypes.add(methodTypeSignatureInternal.parameterTypeSignatures.get(internalParamIdx));
                } else {
                    if (paramIdx == methodTypeSignature.parameterTypeSignatures.size()) {
                        // Shouldn't happen
                        throw new IllegalArgumentException(
                                "Ran out of parameters in programmer-visible type signature");
                    }
                    // This parameter should be present in both type signatures, and the types
                    // should be the same, ignoring any type parameters.
                    final TypeSignature paramTypeSignature = methodTypeSignature.parameterTypeSignatures
                            .get(paramIdx++);
                    final TypeSignature paramTypeSignatureInternal = //
                            methodTypeSignatureInternal.parameterTypeSignatures.get(internalParamIdx);
                    if (!paramTypeSignature.equalsIgnoringTypeParams(paramTypeSignatureInternal)) {
                        throw new IllegalArgumentException(
                                "Corresponding type parameters in type signatures do not refer to the same bare "
                                        + "types: " + paramTypeSignature + " [from method signature "
                                        + methodTypeSignature + "] vs. " + paramTypeSignatureInternal
                                        + " [from method signature " + methodTypeSignatureInternal + "]");
                    }
                    // The programmer-visible parameter should always have more type information, if available
                    mergedParamTypes.add(paramTypeSignature);
                }
            }
            if (paramIdx < methodTypeSignature.parameterTypeSignatures.size()) {
                throw new IllegalArgumentException(
                        "Parameter arity mismatch between internal and programmer-visible type signature");
            }
        }
        List<ClassRefOrTypeVariableSignature> mergedThrowsSignatures;
        if (methodTypeSignature.throwsSignatures.isEmpty()) {
            mergedThrowsSignatures = methodTypeSignatureInternal.throwsSignatures;
        } else if (methodTypeSignatureInternal.throwsSignatures.isEmpty()
                || methodTypeSignature.throwsSignatures.equals(methodTypeSignatureInternal.throwsSignatures)) {
            mergedThrowsSignatures = methodTypeSignature.throwsSignatures;
        } else {
            final AdditionOrderedSet<ClassRefOrTypeVariableSignature> sigSet = new AdditionOrderedSet<>(
                    methodTypeSignature.throwsSignatures);
            sigSet.addAll(methodTypeSignatureInternal.throwsSignatures);
            mergedThrowsSignatures = sigSet.toList();
        }
        return new MethodTypeSignature(
                // Use the programmer-view of type parameters (the JDK-internal view should have no type params)
                methodTypeSignature.typeParameters,
                // Merged parameter types
                mergedParamTypes,
                // Use the programmer-view of result type, in case there is a type parameter
                methodTypeSignature.resultType,
                // Merged throws signatures
                mergedThrowsSignatures);
    }

    /**
     * Parse a method signature (ignores class context, i.e. no ClassInfo needs to be provided -- this means that
     * type variables cannot be resolved to the matching type parameter).
     */
    public static MethodTypeSignature parse(final String typeDescriptor) {
        return MethodTypeSignature.parse(/* classInfo = */ null, typeDescriptor);
    }

    /** Parse a method signature. */
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
                            throwsSignatures.add(classTypeSignature);
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