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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public class TypeParser {
    /**
     * A type signature for a reference type or base type. Subclasses are ReferenceTypeSignature
     * (ClassTypeSignature, TypeVariableSignature, or ArrayTypeSignature) and BaseTypeSignature.
     */
    public abstract static class TypeSignature {
        /**
         * Instantiate the type signature into a class reference. The ScanResult is used to ensure the correct
         * classloader is used to load the class.
         */
        public abstract Class<?> instantiate(final ScanResult scanResult);

        /** Compare base types, ignoring generic type parameters. */
        public abstract boolean equalsIgnoringTypeParams(final TypeSignature other);

        private static TypeSignature parseJavaTypeSignature(final ParseState parseState) throws ParseException {
            final ReferenceTypeSignature referenceTypeSignature = ReferenceTypeSignature
                    .parseReferenceTypeSignature(parseState);
            if (referenceTypeSignature != null) {
                return referenceTypeSignature;
            }
            final BaseTypeSignature baseTypeSignature = BaseTypeSignature.parseBaseType(parseState);
            if (baseTypeSignature != null) {
                return baseTypeSignature;
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A type signature for a base type. */
    public static class BaseTypeSignature extends TypeSignature {
        /** A base type, such as "int", "float", or "void". */
        public final String baseType;

        public BaseTypeSignature(final String baseType) {
            this.baseType = baseType;
        }

        @Override
        public Class<?> instantiate(final ScanResult scanResult) {
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
                throw new RuntimeException("Unknown base type " + baseType);
            }
        }

        @Override
        public int hashCode() {
            return baseType.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof BaseTypeSignature && ((BaseTypeSignature) obj).baseType.equals(this.baseType);
        }

        @Override
        public boolean equalsIgnoringTypeParams(final TypeSignature other) {
            if (!(other instanceof BaseTypeSignature)) {
                return false;
            }
            return baseType.equals(((BaseTypeSignature) other).baseType);
        }

        @Override
        public String toString() {
            return baseType;
        }

        private static BaseTypeSignature parseBaseType(final ParseState parseState) {
            switch (parseState.peek()) {
            case 'B':
                parseState.next();
                return new BaseTypeSignature("byte");
            case 'C':
                parseState.next();
                return new BaseTypeSignature("char");
            case 'D':
                parseState.next();
                return new BaseTypeSignature("double");
            case 'F':
                parseState.next();
                return new BaseTypeSignature("float");
            case 'I':
                parseState.next();
                return new BaseTypeSignature("int");
            case 'J':
                parseState.next();
                return new BaseTypeSignature("long");
            case 'S':
                parseState.next();
                return new BaseTypeSignature("short");
            case 'Z':
                parseState.next();
                return new BaseTypeSignature("boolean");
            case 'V':
                parseState.next();
                return new BaseTypeSignature("void");
            default:
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A type signature for a reference type. Subclasses are ClassTypeSignature, TypeVariableSignature, and
     * ArrayTypeSignature.
     */
    public abstract static class ReferenceTypeSignature extends TypeSignature {
        private static ReferenceTypeSignature parseReferenceTypeSignature(final ParseState parseState)
                throws ParseException {
            final ClassTypeSignature classTypeSignature = ClassTypeSignature.parseClassTypeSignature(parseState);
            if (classTypeSignature != null) {
                return classTypeSignature;
            }
            final TypeVariableSignature typeVariableSignature = TypeVariableSignature
                    .parseTypeVariableSignature(parseState);
            if (typeVariableSignature != null) {
                return typeVariableSignature;
            }
            final ArrayTypeSignature arrayTypeSignature = ArrayTypeSignature.parseArrayTypeSignature(parseState);
            if (arrayTypeSignature != null) {
                return arrayTypeSignature;
            }
            return null;
        }

        private static ReferenceTypeSignature parseClassBound(final ParseState parseState) throws ParseException {
            parseState.expect(':');
            // May return null if there is no signature after ':' (class bound signature may be empty)
            return parseReferenceTypeSignature(parseState);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class type or type variable. Subclasses are ClassTypeSignature and TypeVariableSignature. */
    public abstract static class ClassTypeOrTypeVariableSignature extends ReferenceTypeSignature {
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A type argument. */
    public static class TypeArgument {
        /** A type wildcard. */
        public static enum WILDCARD {
            NONE, ANY, EXTENDS, SUPER
        };

        /** A wildcard type. */
        public final WILDCARD wildcard;
        /** Type signature (will be null if wildcard == ANY). */
        public final ReferenceTypeSignature typeSignature;

        public TypeArgument(final WILDCARD wildcard, final ReferenceTypeSignature typeSignature) {
            this.wildcard = wildcard;
            this.typeSignature = typeSignature;
        }

        @Override
        public int hashCode() {
            return typeSignature.hashCode() + 7 * wildcard.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof TypeArgument)) {
                return false;
            }
            final TypeArgument o = (TypeArgument) obj;
            return (o.typeSignature.equals(this.typeSignature) && o.wildcard.equals(this.wildcard));
        }

        @Override
        public String toString() {
            final String typeSigStr = typeSignature == null ? null : typeSignature.toString();
            switch (wildcard) {
            case ANY:
                return "?";
            case EXTENDS:
                return typeSigStr.equals("java.lang.Object") ? "?" : "? extends " + typeSigStr;
            case SUPER:
                return "? super " + typeSigStr;
            case NONE:
                return typeSigStr;
            default:
                throw new RuntimeException("Unknown wildcard type");
            }
        }

        private static TypeArgument parseTypeArgument(final ParseState parseState) throws ParseException {
            final char peek = parseState.peek();
            if (peek == '*') {
                parseState.expect('*');
                return new TypeArgument(WILDCARD.ANY, null);
            } else if (peek == '+') {
                parseState.expect('+');
                final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                        .parseReferenceTypeSignature(parseState);
                if (typeSignature == null) {
                    throw new ParseException();
                }
                return new TypeArgument(WILDCARD.EXTENDS, typeSignature);
            } else if (peek == '-') {
                parseState.expect('-');
                final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                        .parseReferenceTypeSignature(parseState);
                if (typeSignature == null) {
                    throw new ParseException();
                }
                return new TypeArgument(WILDCARD.SUPER, typeSignature);
            } else {
                final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                        .parseReferenceTypeSignature(parseState);
                if (typeSignature == null) {
                    throw new ParseException();
                }
                return new TypeArgument(WILDCARD.NONE, typeSignature);
            }
        }

        private static List<TypeArgument> parseTypeArguments(final ParseState parseState) throws ParseException {
            if (parseState.peek() == '<') {
                parseState.expect('<');
                final List<TypeArgument> typeArguments = new ArrayList<>();
                while (parseState.peek() != '>') {
                    if (!parseState.hasMore()) {
                        throw new ParseException();
                    }
                    typeArguments.add(parseTypeArgument(parseState));
                }
                parseState.expect('>');
                return typeArguments;
            } else {
                return Collections.emptyList();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class type signature. */
    public static class ClassTypeSignature extends ClassTypeOrTypeVariableSignature {
        /** The class name. */
        public final String className;
        /** The class type arguments. */
        public final List<TypeArgument> typeArguments;
        /** The class type signature suffix(es), or the empty list if no suffixes. */
        public final List<String> suffixes;
        /**
         * The suffix type arguments, one per suffix, or the empty list if no suffixes. The element value will be
         * the empty list if there is no type argument for a given suffix.
         */
        public final List<List<TypeArgument>> suffixTypeArguments;

        public ClassTypeSignature(final String className, final List<TypeArgument> typeArguments,
                final List<String> suffixes, final List<List<TypeArgument>> suffixTypeArguments) {
            this.className = className;
            this.typeArguments = typeArguments;
            this.suffixes = suffixes;
            this.suffixTypeArguments = suffixTypeArguments;
        }

        /** Instantiate class ref. Type arguments are ignored. */
        @Override
        public Class<?> instantiate(final ScanResult scanResult) {
            // TODO: I'm not sure if this is the right thing to do with suffixes (append them to class name)
            final StringBuilder buf = new StringBuilder();
            buf.append(className);
            for (int i = 0; i < suffixes.size(); i++) {
                buf.append(".");
                buf.append(suffixes.get(i));
            }
            final String classNameWithSuffixes = buf.toString();
            return scanResult.classNameToClassRef(classNameWithSuffixes);
        }

        @Override
        public int hashCode() {
            return className.hashCode() + 7 * typeArguments.hashCode() + 15 * suffixes.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof ClassTypeSignature)) {
                return false;
            }
            final ClassTypeSignature o = (ClassTypeSignature) obj;
            return o.className.equals(this.className) && o.typeArguments.equals(this.typeArguments)
                    && o.suffixes.equals(this.suffixes);
        }

        @Override
        public boolean equalsIgnoringTypeParams(final TypeSignature other) {
            if (!(other instanceof ClassTypeSignature)) {
                return false;
            }
            final ClassTypeSignature o = (ClassTypeSignature) other;
            return o.className.equals(this.className) && o.suffixes.equals(this.suffixes);
        }

        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
            buf.append(className);
            if (!typeArguments.isEmpty()) {
                buf.append('<');
                for (int i = 0; i < typeArguments.size(); i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    buf.append(typeArguments.get(i).toString());
                }
                buf.append('>');
            }
            for (int i = 0; i < suffixes.size(); i++) {
                buf.append(".");
                buf.append(suffixes.get(i));
                final List<TypeArgument> suffixTypeArgs = suffixTypeArguments.get(i);
                if (!suffixTypeArgs.isEmpty()) {
                    buf.append('<');
                    for (int j = 0; j < suffixTypeArgs.size(); j++) {
                        if (j > 0) {
                            buf.append(", ");
                        }
                        buf.append(suffixTypeArgs.get(j).toString());
                    }
                    buf.append('>');
                }
            }
            return buf.toString();
        }

        private static ClassTypeSignature parseClassTypeSignature(final ParseState parseState)
                throws ParseException {
            if (parseState.peek() == 'L') {
                parseState.next();
                // if (parseState.peekMatches("java/lang/") || parseState.peekMatches("java/util/")) {
                // parseState.advance(10); }
                if (!parseState.parseIdentifier(/* separator = */ '/', /* separatorReplace = */ '.')) {
                    throw new ParseException();
                }
                final String className = parseState.currToken();
                final List<TypeArgument> typeArguments = TypeArgument.parseTypeArguments(parseState);
                List<String> suffixes;
                List<List<TypeArgument>> suffixTypeArguments;
                if (parseState.peek() == '.') {
                    suffixes = new ArrayList<>();
                    suffixTypeArguments = new ArrayList<>();
                    while (parseState.peek() == '.') {
                        parseState.expect('.');
                        if (!parseState.parseIdentifier(/* separator = */ '/', /* separatorReplace = */ '.')) {
                            throw new ParseException();
                        }
                        suffixes.add(parseState.currToken());
                        suffixTypeArguments.add(TypeArgument.parseTypeArguments(parseState));
                    }
                } else {
                    suffixes = Collections.emptyList();
                    suffixTypeArguments = Collections.emptyList();
                }
                parseState.expect(';');
                return new ClassTypeSignature(className, typeArguments, suffixes, suffixTypeArguments);
            } else {
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A type variable signature. */
    public static class TypeVariableSignature extends ClassTypeOrTypeVariableSignature {
        /** The type variable signature. */
        public final String typeVariableSignature;

        public TypeVariableSignature(final String typeVariableSignature) {
            this.typeVariableSignature = typeVariableSignature;
        }

        @Override
        public Class<?> instantiate(final ScanResult scanResult) {
            throw new RuntimeException("Cannot instantiate a type variable");
        }

        @Override
        public int hashCode() {
            return typeVariableSignature.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof TypeVariableSignature)) {
                return false;
            }
            final TypeVariableSignature o = (TypeVariableSignature) obj;
            return o.typeVariableSignature.equals(this.typeVariableSignature);
        }

        @Override
        public boolean equalsIgnoringTypeParams(final TypeSignature other) {
            // This method shouldn't get called, since it is only for comparing concrete types
            return equals(other);
        }

        @Override
        public String toString() {
            return typeVariableSignature;
        }

        private static TypeVariableSignature parseTypeVariableSignature(final ParseState parseState)
                throws ParseException {
            final char peek = parseState.peek();
            if (peek == 'T') {
                parseState.next();
                if (!parseState.parseIdentifier()) {
                    throw new ParseException();
                }
                parseState.expect(';');
                return new TypeVariableSignature(parseState.currToken());
            } else {
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An array type. */
    public static class ArrayTypeSignature extends ReferenceTypeSignature {
        /** The array element type signature. */
        public final TypeSignature elementTypeSignature;
        /** The number of array dimensions. */
        public final int numArrayDims;

        public ArrayTypeSignature(final TypeSignature elementTypeSignature, final int numArrayDims) {
            this.elementTypeSignature = elementTypeSignature;
            this.numArrayDims = numArrayDims;
        }

        private static Class<?> arrayify(final Class<?> cls, final int arrayDims) {
            if (arrayDims == 0) {
                return cls;
            } else {
                final int[] zeroes = (int[]) Array.newInstance(int.class, arrayDims);
                return Array.newInstance(cls, zeroes).getClass();
            }
        }

        @Override
        public Class<?> instantiate(final ScanResult scanResult) {
            final Class<?> elementClassRef = elementTypeSignature.instantiate(scanResult);
            return arrayify(elementClassRef, numArrayDims);
        }

        @Override
        public int hashCode() {
            return elementTypeSignature.hashCode() + numArrayDims * 15;
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof ArrayTypeSignature)) {
                return false;
            }
            final ArrayTypeSignature o = (ArrayTypeSignature) obj;
            return o.elementTypeSignature.equals(this.elementTypeSignature) && o.numArrayDims == this.numArrayDims;
        }

        @Override
        public boolean equalsIgnoringTypeParams(final TypeSignature other) {
            return equals(other);
        }

        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
            buf.append(elementTypeSignature.toString());
            for (int i = 0; i < numArrayDims; i++) {
                buf.append("[]");
            }
            return buf.toString();
        }

        private static ArrayTypeSignature parseArrayTypeSignature(final ParseState parseState)
                throws ParseException {
            int numArrayDims = 0;
            while (parseState.peek() == '[') {
                numArrayDims++;
                parseState.next();
            }
            if (numArrayDims > 0) {
                final TypeSignature elementTypeSignature = TypeSignature.parseJavaTypeSignature(parseState);
                if (elementTypeSignature == null) {
                    throw new ParseException();
                }
                return new ArrayTypeSignature(elementTypeSignature, numArrayDims);
            } else {
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A type parameter. */
    public static class TypeParameter {
        /** The type identifier. */
        public final String identifier;
        /** Class bound -- may be null */
        public final ReferenceTypeSignature classBound;
        /** Interface bounds -- may be empty */
        public final List<ReferenceTypeSignature> interfaceBounds;

        public TypeParameter(final String identifier, final ReferenceTypeSignature classBound,
                final List<ReferenceTypeSignature> interfaceBounds) {
            this.identifier = identifier;
            this.classBound = classBound;
            this.interfaceBounds = interfaceBounds;
        }

        @Override
        public int hashCode() {
            return identifier.hashCode() + (classBound == null ? 0 : classBound.hashCode() * 7)
                    + interfaceBounds.hashCode() * 15;
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof TypeParameter)) {
                return false;
            }
            final TypeParameter o = (TypeParameter) obj;
            return o.identifier.equals(this.identifier)
                    && ((o.classBound == null && this.classBound == null)
                            || (o.classBound != null && o.classBound.equals(this.classBound)))
                    && o.interfaceBounds.equals(this.interfaceBounds);
        }

        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
            buf.append(identifier);
            String classBoundStr;
            if (classBound == null) {
                classBoundStr = null;
            } else {
                classBoundStr = classBound.toString();
                if (classBoundStr.equals("java.lang.Object")) {
                    // Don't add "extends java.lang.Object"
                    classBoundStr = null;
                }
            }
            if (classBoundStr != null) {
                buf.append(' ');
                buf.append(classBoundStr);
            }
            for (int i = 0; i < interfaceBounds.size(); i++) {
                if (i > 0 || classBoundStr != null) {
                    buf.append(" &");
                }
                buf.append(interfaceBounds.get(i).toString());
            }
            return buf.toString();
        }

        private static TypeParameter parseTypeParameter(final ParseState parseState) throws ParseException {
            if (!parseState.parseIdentifier()) {
                throw new ParseException();
            }
            final String identifier = parseState.currToken();
            // classBound may be null
            final ReferenceTypeSignature classBound = ReferenceTypeSignature.parseClassBound(parseState);
            List<ReferenceTypeSignature> interfaceBounds;
            if (parseState.peek() == ':') {
                interfaceBounds = new ArrayList<>();
                while (parseState.peek() == ':') {
                    parseState.expect(':');
                    final ReferenceTypeSignature interfaceTypeSignature = ReferenceTypeSignature
                            .parseReferenceTypeSignature(parseState);
                    if (interfaceTypeSignature == null) {
                        throw new ParseException();
                    }
                    interfaceBounds.add(interfaceTypeSignature);
                }
            } else {
                interfaceBounds = Collections.emptyList();
            }
            return new TypeParameter(identifier, classBound, interfaceBounds);
        }

        private static List<TypeParameter> parseTypeParameters(final ParseState parseState) throws ParseException {
            if (parseState.peek() != '<') {
                return Collections.emptyList();
            }
            parseState.expect('<');
            final List<TypeParameter> typeParams = new ArrayList<>(1);
            while (parseState.peek() != '>') {
                if (!parseState.hasMore()) {
                    throw new ParseException();
                }
                typeParams.add(parseTypeParameter(parseState));
            }
            parseState.expect('>');
            return typeParams;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A method signature. */
    public static class MethodSignature {
        /** The method type parameters. */
        public final List<TypeParameter> typeParameters;
        /** The method parameter types. */
        public final List<TypeSignature> paramTypes;
        /** The method result type. */
        public final TypeSignature resultType;
        /** The throws type signatures. */
        public final List<ClassTypeOrTypeVariableSignature> throwsSignatures;

        public MethodSignature(final List<TypeParameter> typeParameters, final List<TypeSignature> paramTypes,
                final TypeSignature resultType, final List<ClassTypeOrTypeVariableSignature> throwsSignatures) {
            this.typeParameters = typeParameters;
            this.paramTypes = paramTypes;
            this.resultType = resultType;
            this.throwsSignatures = throwsSignatures;
        }

        @Override
        public int hashCode() {
            return typeParameters.hashCode() + paramTypes.hashCode() * 7 + resultType.hashCode() * 15
                    + throwsSignatures.hashCode() * 31;
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof MethodSignature)) {
                return false;
            }
            final MethodSignature o = (MethodSignature) obj;
            return o.typeParameters.equals(this.typeParameters) && o.paramTypes.equals(this.paramTypes)
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

            if ((parameterNames != null && paramTypes.size() != parameterNames.length)
                    || (parameterAccessFlags != null && paramTypes.size() != parameterAccessFlags.length)
                    || (parameterAnnotationInfo != null && paramTypes.size() != parameterAnnotationInfo.length)) {
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
                buf.append(modifiersToString(modifiers, /* isMethod = */ true));
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
            for (int i = 0; i < paramTypes.size(); i++) {
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
                    if ((flag & 0x0010) != 0) {
                        buf.append("final ");
                    }
                    if ((flag & 0x1000) != 0) {
                        buf.append("synthetic ");
                    }
                    if ((flag & 0x8000) != 0) {
                        buf.append("mandated ");
                    }
                }

                final TypeSignature paramType = paramTypes.get(i);
                if (isVarArgs && (i == paramTypes.size() - 1)) {
                    // Show varargs params correctly
                    if (!(paramType instanceof ArrayTypeSignature)) {
                        throw new IllegalArgumentException(
                                "Got non-array type for last parameter of varargs method " + methodName);
                    }
                    final ArrayTypeSignature arrayType = (ArrayTypeSignature) paramType;
                    if (arrayType.numArrayDims == 0) {
                        throw new IllegalArgumentException(
                                "Got a zero-dimension array type for last parameter of varargs method "
                                        + methodName);
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
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Merge together programmer-view and JDK-internal method type signatures.
     * 
     * @param methodTypeSignature
     *            The programmer-view type signature, with type parameters where possible, and without synthetic or
     *            mandated parameters.
     * @param methodTypeSignatureInternal
     *            The JDK-internal type signature, without type parameters, but including synthetic or mandated
     *            parameters, if any.
     * @param parameterAccessFlags
     *            The parameter modifiers for parameters in the JDK-internal type signature.
     * @return A MethodSignature consisting of all information from both type signatures.
     */
    public static MethodSignature merge(final MethodSignature methodTypeSignature,
            final MethodSignature methodTypeSignatureInternal, final int[] parameterAccessFlags) {
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
        if (parameterAccessFlags != null
                && parameterAccessFlags.length != methodTypeSignatureInternal.paramTypes.size()) {
            throw new IllegalArgumentException(
                    "Parameter arity mismatch between access flags and internal param types");
        }
        List<TypeSignature> mergedParamTypes;
        if (parameterAccessFlags == null) {
            // If there are no parameter access flags, there must be no difference in the number
            // of parameters between the JDK-internal and programmer-visible type signature
            // (i.e. if there are synthetic or mandated parameters, then the classfile should
            // specify this by adding the parameter modifier flags section to the method
            // attributes). It's possible this is not always true, so if this exception is
            // thrown, please report a bug in the GitHub bug tracker.
            if (methodTypeSignature.paramTypes.size() != methodTypeSignatureInternal.paramTypes.size()) {
                throw new IllegalArgumentException("Unexpected mismatch in method paramTypes arity");
            }
            // Use the programmer-visible paramTypes, since these will have type info if it is available
            mergedParamTypes = methodTypeSignature.paramTypes;
        } else {
            mergedParamTypes = new ArrayList<>(methodTypeSignatureInternal.paramTypes.size());
            int internalParamIdx = 0;
            int paramIdx = 0;
            for (; internalParamIdx < methodTypeSignatureInternal.paramTypes.size(); internalParamIdx++) {
                // synthetic: 0x1000; mandated: 0x8000
                if ((parameterAccessFlags[internalParamIdx] & 0x9000) != 0) {
                    // This parameter is present in JDK-internal type signature, but not in the 
                    // programmer-visible signature. This should only be true for synthetic and
                    // mandated types, and they should not have any type parameters, due to type
                    // erasure.
                    mergedParamTypes.add(methodTypeSignatureInternal.paramTypes.get(internalParamIdx));
                } else {
                    if (paramIdx == methodTypeSignature.paramTypes.size()) {
                        // Shouldn't happen
                        throw new IllegalArgumentException(
                                "Ran out of parameters in programmer-visible type signature");
                    }
                    // This parameter should be present in both type signatures, and the types
                    // should be the same, ignoring any type parameters.
                    final TypeSignature paramTypeSignature = methodTypeSignature.paramTypes.get(paramIdx++);
                    final TypeSignature paramTypeSignatureInternal = methodTypeSignatureInternal.paramTypes
                            .get(internalParamIdx);
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
            if (paramIdx < methodTypeSignature.paramTypes.size()) {
                throw new IllegalArgumentException(
                        "Parameter arity mismatch between internal and programmer-visible type signature");
            }
        }
        List<ClassTypeOrTypeVariableSignature> mergedThrowsSignatures;
        if (methodTypeSignature.throwsSignatures.isEmpty()) {
            mergedThrowsSignatures = methodTypeSignatureInternal.throwsSignatures;
        } else if (methodTypeSignatureInternal.throwsSignatures.isEmpty()
                || methodTypeSignature.throwsSignatures.equals(methodTypeSignatureInternal.throwsSignatures)) {
            mergedThrowsSignatures = methodTypeSignature.throwsSignatures;
        } else {
            final AdditionOrderedSet<ClassTypeOrTypeVariableSignature> sigSet = new AdditionOrderedSet<>(
                    methodTypeSignature.throwsSignatures);
            sigSet.addAll(methodTypeSignatureInternal.throwsSignatures);
            mergedThrowsSignatures = sigSet.toList();
        }
        return new MethodSignature(
                // Use the programmer-view of type parameters (the JDK-internal view should have no type params)
                methodTypeSignature.typeParameters,
                // Merged parameter types
                mergedParamTypes,
                // Use the programmer-view of result type, in case there is a type parameter
                methodTypeSignature.resultType,
                // Merged throws signatures
                mergedThrowsSignatures);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class signature. */
    public static class ClassSignature {
        /** The class type parameters. */
        public final List<TypeParameter> typeParameters;
        /** The superclass type. */
        public final ClassTypeSignature superclassSignature;
        /** The superinterface signatures. */
        public final List<ClassTypeSignature> superinterfaceSignatures;

        public ClassSignature(final List<TypeParameter> typeParameters,
                final ClassTypeSignature superclassSignature,
                final List<ClassTypeSignature> superinterfaceSignatures) {
            this.typeParameters = typeParameters;
            this.superclassSignature = superclassSignature;
            this.superinterfaceSignatures = superinterfaceSignatures;
        }

        @Override
        public int hashCode() {
            return typeParameters.hashCode() + superclassSignature.hashCode() * 7
                    + superinterfaceSignatures.hashCode() * 15;
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof ClassSignature)) {
                return false;
            }
            final ClassSignature o = (ClassSignature) obj;
            return o.typeParameters.equals(this.typeParameters)
                    && o.superclassSignature.equals(this.superclassSignature)
                    && o.superinterfaceSignatures.equals(this.superinterfaceSignatures);
        }

        public String toString(final int modifiers, final String className) {
            final StringBuilder buf = new StringBuilder();
            if (modifiers != 0) {
                buf.append(modifiersToString(modifiers, /* isMethod = */ false));
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
                buf.append("> ");
            }
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("class");
            if (className != null) {
                buf.append(' ');
                buf.append(className);
            }
            if (superclassSignature != null) {
                buf.append(" extends");
                buf.append(superclassSignature.toString());
            }
            if (!superinterfaceSignatures.isEmpty()) {
                buf.append(" implements");
                for (int i = 0; i < superinterfaceSignatures.size(); i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    buf.append(superinterfaceSignatures.get(i).toString());
                }
            }
            return buf.toString();
        }

        @Override
        public String toString() {
            return toString(/* modifiers = */ 0, /* methodName = */ null);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Convert field or method modifiers into a string representation, e.g. "public static final". */
    public static String modifiersToString(final int modifiers, final boolean isMethod) {
        final StringBuilder buf = new StringBuilder();
        if ((modifiers & Modifier.PUBLIC) != 0) {
            buf.append("public");
        } else if ((modifiers & Modifier.PROTECTED) != 0) {
            buf.append("protected");
        } else if ((modifiers & Modifier.PRIVATE) != 0) {
            buf.append("private");
        }
        if ((modifiers & Modifier.STATIC) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("static");
        }
        if ((modifiers & Modifier.ABSTRACT) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("abstract");
        }
        if ((modifiers & Modifier.SYNCHRONIZED) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("synchronized");
        }
        if (!isMethod && (modifiers & Modifier.TRANSIENT) != 0) {
            // TRANSIENT has the same value as VARARGS, since they are mutually exclusive (TRANSIENT applies only to
            // fields, VARARGS applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("transient");
        } else if ((modifiers & Modifier.VOLATILE) != 0) {
            // VOLATILE has the same value as BRIDGE, since they are mutually exclusive (VOLATILE applies only to
            // fields, BRIDGE applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            if (!isMethod) {
                buf.append("volatile");
            } else {
                buf.append("bridge");
            }
        }
        if ((modifiers & Modifier.FINAL) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("final");
        }
        if ((modifiers & Modifier.NATIVE) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("native");
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("serial")
    private static class ParseException extends Exception {
    }

    private static class ParseState {
        private final String string;
        private int position;
        private final StringBuilder token = new StringBuilder();

        public ParseState(final String string) {
            this.string = string;
        }

        public char getc() {
            if (position >= string.length()) {
                return '\0';
            }
            return string.charAt(position++);
        }

        public char peek() {
            return position == string.length() ? '\0' : string.charAt(position);
        }

        @SuppressWarnings("unused")
        public boolean peekMatches(final String strMatch) {
            return string.regionMatches(position, strMatch, 0, strMatch.length());
        }

        public void next() {
            position++;
        }

        @SuppressWarnings("unused")
        public void advance(final int n) {
            position += n;
        }

        public boolean hasMore() {
            return position < string.length();
        }

        public void expect(final char c) {
            final int next = getc();
            if (next != c) {
                throw new IllegalArgumentException(
                        "Got character '" + (char) next + "', expected '" + c + "' in string: " + this);
            }
        }

        @SuppressWarnings("unused")
        public void appendToToken(final String str) {
            token.append(str);
        }

        public void appendToToken(final char c) {
            token.append(c);
        }

        /** Get the current token, and reset the token to empty. */
        public String currToken() {
            final String tok = token.toString();
            token.setLength(0);
            return tok;
        }

        public boolean parseIdentifier(final char separator, final char separatorReplace) throws ParseException {
            boolean consumedChar = false;
            while (hasMore()) {
                final char c = peek();
                if (c == separator) {
                    appendToToken(separatorReplace);
                    next();
                    consumedChar = true;
                } else if (c != ';' && c != '[' && c != '<' && c != '>' && c != ':' && c != '/' && c != '.') {
                    appendToToken(c);
                    next();
                    consumedChar = true;
                } else {
                    break;
                }
            }
            return consumedChar;
        }

        public boolean parseIdentifier() throws ParseException {
            return parseIdentifier('\0', '\0');
        }

        @Override
        public String toString() {
            return string + " (position: " + position + "; token: \"" + token + "\")";
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Parse a method signature. */
    public static MethodSignature parseMethodSignature(final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        try {
            final List<TypeParameter> typeParameters = TypeParameter.parseTypeParameters(parseState);
            parseState.expect('(');
            final List<TypeSignature> paramTypes = new ArrayList<>();
            while (parseState.peek() != ')') {
                if (!parseState.hasMore()) {
                    throw new ParseException();
                }
                final TypeSignature paramType = TypeSignature.parseJavaTypeSignature(parseState);
                if (paramType == null) {
                    throw new ParseException();
                }
                paramTypes.add(paramType);
            }
            parseState.expect(')');
            final TypeSignature resultType = TypeSignature.parseJavaTypeSignature(parseState);
            if (resultType == null) {
                throw new ParseException();
            }
            List<ClassTypeOrTypeVariableSignature> throwsSignatures;
            if (parseState.peek() == '^') {
                throwsSignatures = new ArrayList<>();
                while (parseState.peek() == '^') {
                    parseState.expect('^');
                    final ClassTypeSignature classTypeSignature = ClassTypeSignature
                            .parseClassTypeSignature(parseState);
                    if (classTypeSignature != null) {
                        throwsSignatures.add(classTypeSignature);
                    } else {
                        final TypeVariableSignature typeVariableSignature = TypeVariableSignature
                                .parseTypeVariableSignature(parseState);
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
            return new MethodSignature(typeParameters, paramTypes, resultType, throwsSignatures);
        } catch (final Exception e) {
            throw new RuntimeException("Type signature could not be parsed: " + parseState, e);
        }
    }

    /**
     * Parse a class signature.
     *
     * <p>
     * TODO this is not currently used -- I don't know where this is even used in the classfile format. The JVMS
     * spec section 4.7.9.1 states "A class signature encodes type information about a (possibly generic) class
     * declaration."
     */
    public static ClassSignature parseClassSignature(final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        try {
            final List<TypeParameter> typeParameters = TypeParameter.parseTypeParameters(parseState);
            final ClassTypeSignature superclassSignature = ClassTypeSignature.parseClassTypeSignature(parseState);
            List<ClassTypeSignature> superinterfaceSignatures;
            if (parseState.hasMore()) {
                superinterfaceSignatures = new ArrayList<>();
                while (parseState.hasMore()) {
                    final ClassTypeSignature superinterfaceSignature = ClassTypeSignature
                            .parseClassTypeSignature(parseState);
                    if (superinterfaceSignature == null) {
                        throw new ParseException();
                    }
                    superinterfaceSignatures.add(superinterfaceSignature);
                }
            } else {
                superinterfaceSignatures = Collections.emptyList();
            }
            return new ClassSignature(typeParameters, superclassSignature, superinterfaceSignatures);
        } catch (final Exception e) {
            throw new RuntimeException("Type signature could not be parsed: " + parseState, e);
        }
    }

    /** Parse a type signature. */
    public static TypeSignature parseTypeSignature(final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        if (parseState.peek() == '(') {
            // This method is not for method signatures, use parseComplexTypeDescriptor() instead
            throw new RuntimeException("Got unexpected method signature");
        }
        TypeSignature typeSignature;
        try {
            typeSignature = TypeSignature.parseJavaTypeSignature(parseState);
            if (typeSignature == null) {
                throw new ParseException();
            }
        } catch (final Exception e) {
            throw new RuntimeException("Type signature could not be parsed: " + parseState, e);
        }
        if (parseState.hasMore()) {
            throw new RuntimeException("Unused characters in type signature: " + parseState);
        }
        return typeSignature;
    }
}
