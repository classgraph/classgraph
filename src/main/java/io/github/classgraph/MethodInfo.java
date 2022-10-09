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

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.ClassInfo.RelType;
import io.github.classgraph.Classfile.MethodTypeAnnotationDecorator;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.TypeUtils;
import nonapi.io.github.classgraph.types.TypeUtils.ModifierType;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends ClassMemberInfo implements Comparable<MethodInfo> {
    /** The parsed type descriptor. */
    private transient MethodTypeSignature typeDescriptor;

    /** The parsed type signature (or null if none). Method parameter types are unaligned. */
    private transient MethodTypeSignature typeSignature;

    /**
     * Unaligned parameter names. These are only produced in JDK8+, and only if the commandline switch `-parameters`
     * is provided at compiletime.
     */
    private String[] parameterNames;

    /**
     * Unaligned parameter modifiers. These are only produced in JDK8+, and only if the commandline switch
     * `-parameters` is provided at compiletime.
     */
    private int[] parameterModifiers;

    /** Unaligned parameter annotations. */
    AnnotationInfo[][] parameterAnnotationInfo;

    /** Aligned method parameter info. */
    private transient MethodParameterInfo[] parameterInfo;

    /** True if this method has a body. */
    private boolean hasBody;

    /** The minimum line number for the body of this method, or 0 if unknown. */
    private int minLineNum;

    /** The maximum line number for the body of this method, or 0 if unknown. */
    private int maxLineNum;

    /** The type annotation decorators for the {@link MethodTypeSignature} instance. */
    private transient List<MethodTypeAnnotationDecorator> typeAnnotationDecorators;

    private String[] thrownExceptionNames;

    private transient ClassInfoList thrownExceptions;

    // -------------------------------------------------------------------------------------------------------------

    /** Default constructor for deserialization. */
    MethodInfo() {
        super();
    }

    /**
     * Constructor.
     *
     * @param definingClassName
     *            The name of the enclosing class.
     * @param methodName
     *            The name of the method.
     * @param methodAnnotationInfo
     *            The list of {@link AnnotationInfo} objects for any annotations on the method.
     * @param modifiers
     *            The method modifier bits.
     * @param typeDescriptorStr
     *            The internal method type descriptor string.
     * @param typeSignatureStr
     *            The internal method type signature string, or null if none.
     * @param parameterNames
     *            The parameter names.
     * @param parameterModifiers
     *            The parameter modifiers.
     * @param parameterAnnotationInfo
     *            The parameter {@link AnnotationInfo}.
     * @param hasBody
     *            True if this method has a body.
     * @param minLineNum
     *            The minimum line number for the body of this method, or 0 if unknown.
     * @param maxLineNum
     *            The maximum line number for the body of this method, or 0 if unknown.
     * @param methodTypeAnnotationDecorators
     *            Decorator lambdas for method type annotations.
     * @param thrownExceptionNames
     *            exceptions thrown by this method.
     */
    MethodInfo(final String definingClassName, final String methodName,
            final AnnotationInfoList methodAnnotationInfo, final int modifiers, final String typeDescriptorStr,
            final String typeSignatureStr, final String[] parameterNames, final int[] parameterModifiers,
            final AnnotationInfo[][] parameterAnnotationInfo, final boolean hasBody, final int minLineNum,
            final int maxLineNum, final List<MethodTypeAnnotationDecorator> methodTypeAnnotationDecorators,
            final String[] thrownExceptionNames) {
        super(definingClassName, methodName, modifiers, typeDescriptorStr, typeSignatureStr, methodAnnotationInfo);
        this.parameterNames = parameterNames;
        this.parameterModifiers = parameterModifiers;
        this.parameterAnnotationInfo = parameterAnnotationInfo;
        this.hasBody = hasBody;
        this.minLineNum = minLineNum;
        this.maxLineNum = maxLineNum;
        this.typeAnnotationDecorators = methodTypeAnnotationDecorators;
        this.thrownExceptionNames = thrownExceptionNames;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the name of the method. Note that constructors are named {@code "<init>"}, and private static class
     * initializer blocks are named {@code "<clinit>"}.
     * 
     * @return The name of the method.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get the method modifiers as a String, e.g. "public static final". For the modifier bits, call
     * {@link #getModifiers()}.
     * 
     * @return The modifiers for the method, as a String.
     */
    @Override
    public String getModifiersStr() {
        final StringBuilder buf = new StringBuilder();
        TypeUtils.modifiersToString(modifiers, ModifierType.METHOD, isDefault(), buf);
        return buf.toString();
    }

    /**
     * Returns the parsed type descriptor for the method, which will not include type parameters. If you need
     * generic type parameters, call {@link #getTypeSignature()} instead.
     * 
     * @return The parsed type descriptor for the method.
     */
    @Override
    public MethodTypeSignature getTypeDescriptor() {
        if (typeDescriptor == null) {
            try {
                typeDescriptor = MethodTypeSignature.parse(typeDescriptorStr, declaringClassName);
                typeDescriptor.setScanResult(scanResult);
                if (typeAnnotationDecorators != null) {
                    // It is possible that there are extra implicit params added at the beginning of the
                    // parameter list that type annotations don't take into account. Assume that the
                    // type signature has the correct number of parameters, and temporarily remove any
                    // implicit prefix parameters during the type decoration process. See getParameterInfo().
                    int sigNumParam = 0;
                    final MethodTypeSignature sig = getTypeSignature();
                    if (sig == null) {
                        // There is no type signature -- run type annotation decorators on descriptor
                        for (final MethodTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                            decorator.decorate(typeDescriptor);
                        }
                    } else {
                        // Determine how many extra implicit params there are
                        sigNumParam = sig.getParameterTypeSignatures().size();
                        final int descNumParam = typeDescriptor.getParameterTypeSignatures().size();
                        final int numImplicitPrefixParams = descNumParam - sigNumParam;
                        if (numImplicitPrefixParams < 0) {
                            // Sanity check -- should not happen
                            throw new IllegalArgumentException(
                                    "Fewer params in method type descriptor than in method type signature");
                        } else if (numImplicitPrefixParams == 0) {
                            // There are no implicit prefix params -- run type annotation decorators on descriptor
                            for (final MethodTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                                decorator.decorate(typeDescriptor);
                            }
                        } else {
                            // There are implicit prefix params -- strip them temporarily from type descriptor,
                            // then run decorators, then add them back again
                            final List<TypeSignature> paramSigs = typeDescriptor.getParameterTypeSignatures();
                            final List<TypeSignature> strippedParamSigs = paramSigs.subList(0,
                                    numImplicitPrefixParams);
                            for (int i = 0; i < numImplicitPrefixParams; i++) {
                                paramSigs.remove(0);
                            }
                            for (final MethodTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                                decorator.decorate(typeDescriptor);
                            }
                            for (int i = numImplicitPrefixParams - 1; i >= 0; --i) {
                                paramSigs.add(0, strippedParamSigs.get(i));
                            }
                        }
                    }
                }
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeDescriptor;
    }

    /**
     * Returns the parsed type signature for the method, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this method, call {@link #getTypeDescriptor()}
     * instead.
     * 
     * @return The parsed type signature for the method, or null if not available.
     * @throws IllegalArgumentException
     *             if the method type signature cannot be parsed (this should only be thrown in the case of
     *             classfile corruption, or a compiler bug that causes an invalid type signature to be written to
     *             the classfile).
     */
    @Override
    public MethodTypeSignature getTypeSignature() {
        if (typeSignature == null && typeSignatureStr != null) {
            try {
                typeSignature = MethodTypeSignature.parse(typeSignatureStr, declaringClassName);
                typeSignature.setScanResult(scanResult);
                if (typeAnnotationDecorators != null) {
                    for (final MethodTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                        decorator.decorate(typeSignature);
                    }
                }
            } catch (final ParseException e) {
                throw new IllegalArgumentException(
                        "Invalid type signature for method " + getClassName() + "." + getName()
                                + (getClassInfo() != null
                                        ? " in classpath element " + getClassInfo().getClasspathElementURI()
                                        : "")
                                + " : " + typeSignatureStr,
                        e);
            }
        }
        return typeSignature;
    }

    /**
     * Returns the parsed type signature for the method, possibly including type parameters. If the type signature
     * string is null, indicating that no type signature information is available for this method, returns the
     * parsed type descriptor instead.
     * 
     * @return The parsed type signature for the method, or if not available, the parsed type descriptor for the
     *         method.
     */
    @Override
    public MethodTypeSignature getTypeSignatureOrTypeDescriptor() {
        MethodTypeSignature typeSig = null;
        try {
            typeSig = getTypeSignature();
            if (typeSig != null) {
                return typeSig;
            }
        } catch (final Exception e) {
            // Ignore
        }
        return getTypeDescriptor();
    }

    /**
     * Returns the list of exceptions thrown by the method, as a {@link ClassInfoList}.
     * 
     * @return The list of exceptions thrown by the method, as a {@link ClassInfoList} (the list may be empty).
     */
    public ClassInfoList getThrownExceptions() {
        if (thrownExceptions == null && thrownExceptionNames != null) {
            thrownExceptions = new ClassInfoList(thrownExceptionNames.length);
            for (final String thrownExceptionName : thrownExceptionNames) {
                final ClassInfo classInfo = scanResult.getClassInfo(thrownExceptionName);
                if (classInfo != null) {
                    thrownExceptions.add(classInfo);
                    classInfo.setScanResult(scanResult);
                }
            }
        }
        return thrownExceptions == null ? ClassInfoList.EMPTY_LIST : thrownExceptions;
    }

    /**
     * Returns the exceptions thrown by the method, as an array.
     * 
     * @return The exceptions thrown by the method, as an array (the array may be empty).
     */
    public String[] getThrownExceptionNames() {
        return thrownExceptionNames == null ? new String[0] : thrownExceptionNames;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns true if this method is a constructor. Constructors have the method name {@code
     * "<init>"}. This returns false for private static class initializer blocks, which are named
     * {@code "<clinit>"}.
     * 
     * @return True if this method is a constructor.
     */
    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    /**
     * Returns true if this method is synchronized.
     * 
     * @return True if this method is synchronized.
     */
    public boolean isSynchronized() {
        return Modifier.isSynchronized(modifiers);
    }

    /**
     * Returns true if this method is a bridge method.
     * 
     * @return True if this is a bridge method.
     */
    public boolean isBridge() {
        return (modifiers & 0x0040) != 0;
    }

    /**
     * Returns true if this method is a varargs method.
     * 
     * @return True if this is a varargs method.
     */
    public boolean isVarArgs() {
        return (modifiers & 0x0080) != 0;
    }

    /**
     * Returns true if this method is a native method.
     * 
     * @return True if this method is native.
     */
    public boolean isNative() {
        return Modifier.isNative(modifiers);
    }

    /**
     * Returns true if this method is abstract.
     * 
     * @return True if this method is abstract.
     */
    public boolean isAbstract() {
        return Modifier.isAbstract(modifiers);
    }

    /**
     * Returns true if this method is strict.
     * 
     * @return True if this method is strict.
     */
    public boolean isStrict() {
        return Modifier.isStrict(modifiers);
    }

    /**
     * Returns true if this method has a body (i.e. has an implementation in the containing class).
     * 
     * @return True if this method has a body.
     */
    public boolean hasBody() {
        return hasBody;
    }

    /**
     * The line number of the first non-empty line in the body of this method, or 0 if unknown.
     * 
     * @return The line number of the first non-empty line in the body of this method, or 0 if unknown.
     */
    public int getMinLineNum() {
        return minLineNum;
    }

    /**
     * The line number of the last non-empty line in the body of this method, or 0 if unknown.
     * 
     * @return The line number of the last non-empty line in the body of this method, or 0 if unknown.
     */
    public int getMaxLineNum() {
        return maxLineNum;
    }

    /**
     * Returns true if this is a default method (i.e. if this is a method in an interface and the method has a
     * body).
     * 
     * @return True if this is a default method.
     */
    public boolean isDefault() {
        final ClassInfo classInfo = getClassInfo();
        return classInfo != null && classInfo.isInterface() && hasBody;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the available information on method parameters.
     * 
     * @return The {@link MethodParameterInfo} objects for the method parameters, one per parameter.
     */
    public MethodParameterInfo[] getParameterInfo() {
        // Kotlin is very inconsistent about the arity of each of the parameter metadata types, see:
        // https://github.com/classgraph/classgraph/issues/175#issuecomment-363031510
        // As a workaround, we assume that any synthetic / mandated parameters must come first in the
        // parameter list, when the arities don't match, and we right-align the metadata fields.
        // This is probably the safest assumption across JVM languages, even though this convention
        // is by no means the only possibility. (Unfortunately we can't just rely on the modifier
        // bits to find synthetic / mandated parameters, because these bits are not always available,
        // and even when they are, they don't always give the right alignment, at least for Kotlin-
        // generated code).

        // Actually the Java spec says specifically: "The signature and descriptor of a given method
        // or constructor may not correspond exactly, due to compiler-generated artifacts. In particular,
        // the number of TypeSignatures that encode formal arguments in MethodTypeSignature may be less
        // than the number of ParameterDescriptors in MethodDescriptor."

        // This was also triggered by an implicit param in Guava 28.2 (#660).

        if (parameterInfo == null) {
            // Get param type signatures from the type signature of the method
            List<TypeSignature> paramTypeSignatures = null;
            final MethodTypeSignature typeSig = getTypeSignature();
            if (typeSig != null) {
                paramTypeSignatures = typeSig.getParameterTypeSignatures();
            }

            // If there is no type signature (i.e. if this is not a generic method), fall back to the type
            // descriptor (N.B. the type descriptor is basically junk, because the compiler may prepend
            // `synthetic` and/or `bridge` parameters automatically, without providing any modifiers for
            // the method, so that it is impossible to know how many parameters have been prepended --
            // see #660.)
            List<TypeSignature> paramTypeDescriptors = null;
            try {
                final MethodTypeSignature typeDesc = getTypeDescriptor();
                if (typeDesc != null) {
                    paramTypeDescriptors = typeDesc.getParameterTypeSignatures();
                }
            } catch (final Exception e) {
                // Ignore any IllegalArgumentExceptions triggered when type annotations are not able to be
                /// aligned with parameters, when there is a `synthetic`, `bridge` or `mandated` parameter
                // added to the first parameter position.
            }

            // Find the max length of all the parameter information sources
            int numParams = paramTypeSignatures == null ? 0 : paramTypeSignatures.size();
            if (paramTypeDescriptors != null && paramTypeDescriptors.size() > numParams) {
                numParams = paramTypeDescriptors.size();
            }
            if (parameterNames != null && parameterNames.length > numParams) {
                numParams = parameterNames.length;
            }
            if (parameterModifiers != null && parameterModifiers.length > numParams) {
                numParams = parameterModifiers.length;
            }
            if (parameterAnnotationInfo != null && parameterAnnotationInfo.length > numParams) {
                numParams = parameterAnnotationInfo.length;
            }

            // "Right-align" all parameter info, i.e. assume that any automatically-added implicit parameters
            // were added at the beginning of the parameter list, not the end.

            String[] paramNamesAligned = null;
            if (parameterNames != null && parameterNames.length > 0) {
                if (parameterNames.length == numParams) {
                    // No alignment necessary
                    paramNamesAligned = parameterNames;
                } else {
                    // Right-align when not the right length
                    paramNamesAligned = new String[numParams];
                    for (int i = 0, lenDiff = numParams - parameterNames.length; i < parameterNames.length; i++) {
                        paramNamesAligned[lenDiff + i] = parameterNames[i];
                    }
                }
            }
            int[] paramModifiersAligned = null;
            if (parameterModifiers != null && parameterModifiers.length > 0) {
                if (parameterModifiers.length == numParams) {
                    // No alignment necessary
                    paramModifiersAligned = parameterModifiers;
                } else {
                    // Right-align when not the right length
                    paramModifiersAligned = new int[numParams];
                    for (int i = 0,
                            lenDiff = numParams - parameterModifiers.length; i < parameterModifiers.length; i++) {
                        paramModifiersAligned[lenDiff + i] = parameterModifiers[i];
                    }
                }
            }
            AnnotationInfo[][] paramAnnotationInfoAligned = null;
            if (parameterAnnotationInfo != null && parameterAnnotationInfo.length > 0) {
                if (parameterAnnotationInfo.length == numParams) {
                    // No alignment necessary
                    paramAnnotationInfoAligned = parameterAnnotationInfo;
                } else {
                    // Right-align when not the right length
                    paramAnnotationInfoAligned = new AnnotationInfo[numParams][];
                    for (int i = 0, lenDiff = numParams
                            - parameterAnnotationInfo.length; i < parameterAnnotationInfo.length; i++) {
                        paramAnnotationInfoAligned[lenDiff + i] = parameterAnnotationInfo[i];
                    }
                }
            }
            List<TypeSignature> paramTypeSignaturesAligned = null;
            if (paramTypeSignatures != null && paramTypeSignatures.size() > 0) {
                if (paramTypeSignatures.size() == numParams) {
                    // No alignment necessary
                    paramTypeSignaturesAligned = paramTypeSignatures;
                } else {
                    // Right-align when not the right length
                    paramTypeSignaturesAligned = new ArrayList<>(numParams);
                    for (int i = 0, lenDiff = numParams - paramTypeSignatures.size(); i < lenDiff; i++) {
                        // Left-pad with nulls
                        paramTypeSignaturesAligned.add(null);
                    }
                    paramTypeSignaturesAligned.addAll(paramTypeSignatures);
                }
            }
            List<TypeSignature> paramTypeDescriptorsAligned = null;
            if (paramTypeDescriptors != null && paramTypeDescriptors.size() > 0) {
                if (paramTypeDescriptors.size() == numParams) {
                    // No alignment necessary
                    paramTypeDescriptorsAligned = paramTypeDescriptors;
                } else {
                    // Right-align when not the right length
                    paramTypeDescriptorsAligned = new ArrayList<>(numParams);
                    for (int i = 0, lenDiff = numParams - paramTypeDescriptors.size(); i < lenDiff; i++) {
                        // Left-pad with nulls
                        paramTypeDescriptorsAligned.add(null);
                    }
                    paramTypeDescriptorsAligned.addAll(paramTypeDescriptors);
                }
            }

            // Generate MethodParameterInfo entries
            parameterInfo = new MethodParameterInfo[numParams];
            for (int i = 0; i < numParams; i++) {
                parameterInfo[i] = new MethodParameterInfo(this,
                        paramAnnotationInfoAligned == null ? null : paramAnnotationInfoAligned[i],
                        paramModifiersAligned == null ? 0 : paramModifiersAligned[i],
                        paramTypeDescriptorsAligned == null ? null : paramTypeDescriptorsAligned.get(i),
                        paramTypeSignaturesAligned == null ? null : paramTypeSignaturesAligned.get(i),
                        paramNamesAligned == null ? null : paramNamesAligned[i]);
                parameterInfo[i].setScanResult(scanResult);
            }
        }
        return parameterInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check if this method has a parameter with the annotation.
     *
     * @param annotation
     *            The method parameter annotation.
     * @return true if this method has a parameter with the annotation.
     */
    public boolean hasParameterAnnotation(final Class<? extends Annotation> annotation) {
        Assert.isAnnotation(annotation);
        return hasParameterAnnotation(annotation.getName());
    }

    /**
     * Check if this method has a parameter with the named annotation.
     *
     * @param annotationName
     *            The name of a method parameter annotation.
     * @return true if this method has a parameter with the named annotation.
     */
    public boolean hasParameterAnnotation(final String annotationName) {
        for (final MethodParameterInfo methodParameterInfo : getParameterInfo()) {
            if (methodParameterInfo.hasAnnotation(annotationName)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load and return the classes of each of the method parameters.
     * 
     * @return An array of the {@link Class} references for each method parameter.
     */
    private Class<?>[] loadParameterClasses() {
        final MethodParameterInfo[] allParameterInfo = getParameterInfo();
        final List<Class<?>> parameterClasses = new ArrayList<>(allParameterInfo.length);
        for (final MethodParameterInfo mpi : allParameterInfo) {
            final TypeSignature parameterType = mpi.getTypeSignatureOrTypeDescriptor();
            TypeSignature actualParameterType;
            if (parameterType instanceof TypeVariableSignature) {
                final TypeVariableSignature tvs = (TypeVariableSignature) parameterType;
                final TypeParameter t = tvs.resolve();
                if (t.classBound != null) {
                    // Use class bound of type variable as concrete type, if available,
                    // in preference to using first interface bound (ignores interface
                    // bound(s), if present)
                    actualParameterType = t.classBound;
                } else if (t.interfaceBounds != null && !t.interfaceBounds.isEmpty()) {
                    // Use first interface bound of type variable as concrete type
                    // (ignores 2nd and subsequent interface bound(s), if present)
                    actualParameterType = t.interfaceBounds.get(0);
                } else {
                    // Sanity check, should not happen
                    throw new IllegalArgumentException("TypeVariableSignature has no bounds");
                }
            } else {
                actualParameterType = parameterType;
            }
            parameterClasses.add(actualParameterType.loadClass());
        }
        return parameterClasses.toArray(new Class<?>[0]);
    }

    /**
     * Load the class this method is associated with, and get the {@link Method} reference for this method. Only
     * call this if {@link #isConstructor()} returns false, otherwise an {@link IllegalArgumentException} will be
     * thrown. Instead call {@link #loadClassAndGetConstructor()} for constructors.
     * 
     * @return The {@link Method} reference for this method.
     * @throws IllegalArgumentException
     *             if the method does not exist, or if the method is a constructor.
     */
    public Method loadClassAndGetMethod() throws IllegalArgumentException {
        if (isConstructor()) {
            throw new IllegalArgumentException(
                    "Need to call loadClassAndGetConstructor() for constructors, not loadClassAndGetMethod()");
        }
        final Class<?>[] parameterClassesArr = loadParameterClasses();
        try {
            return loadClass().getMethod(getName(), parameterClassesArr);
        } catch (final NoSuchMethodException e1) {
            try {
                return loadClass().getDeclaredMethod(getName(), parameterClassesArr);
            } catch (final NoSuchMethodException es2) {
                throw new IllegalArgumentException("Method not found: " + getClassName() + "." + getName());
            }
        }
    }

    /**
     * Load the class this constructor is associated with, and get the {@link Constructor} reference for this
     * constructor. Only call this if {@link #isConstructor()} returns true, otherwise an
     * {@link IllegalArgumentException} will be thrown. Instead call {@link #loadClassAndGetMethod()} for non-method
     * constructors.
     * 
     * @return The {@link Constructor} reference for this constructor.
     * @throws IllegalArgumentException
     *             if the constructor does not exist, or if the method is not a constructor.
     */
    public Constructor<?> loadClassAndGetConstructor() throws IllegalArgumentException {
        if (!isConstructor()) {
            throw new IllegalArgumentException(
                    "Need to call loadClassAndGetMethod() for non-constructor methods, not "
                            + "loadClassAndGetConstructor()");
        }
        final Class<?>[] parameterClassesArr = loadParameterClasses();
        try {
            return loadClass().getConstructor(parameterClassesArr);
        } catch (final NoSuchMethodException e1) {
            try {
                return loadClass().getDeclaredConstructor(parameterClassesArr);
            } catch (final NoSuchMethodException es2) {
                throw new IllegalArgumentException("Constructor not found for class " + getClassName());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle {@link Repeatable} annotations.
     *
     * @param allRepeatableAnnotationNames
     *            the names of all repeatable annotations
     */
    void handleRepeatableAnnotations(final Set<String> allRepeatableAnnotationNames) {
        if (annotationInfo != null) {
            annotationInfo.handleRepeatableAnnotations(allRepeatableAnnotationNames, getClassInfo(),
                    RelType.METHOD_ANNOTATIONS, RelType.CLASSES_WITH_METHOD_ANNOTATION,
                    RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION);
        }
        if (parameterAnnotationInfo != null) {
            for (int i = 0; i < parameterAnnotationInfo.length; i++) {
                final AnnotationInfo[] pai = parameterAnnotationInfo[i];
                if (pai != null && pai.length > 0) {
                    boolean hasRepeatableAnnotation = false;
                    for (final AnnotationInfo ai : pai) {
                        if (allRepeatableAnnotationNames.contains(ai.getName())) {
                            hasRepeatableAnnotation = true;
                            break;
                        }
                    }
                    if (hasRepeatableAnnotation) {
                        final AnnotationInfoList aiList = new AnnotationInfoList(pai.length);
                        aiList.addAll(Arrays.asList(pai));
                        aiList.handleRepeatableAnnotations(allRepeatableAnnotationNames, getClassInfo(),
                                RelType.METHOD_PARAMETER_ANNOTATIONS,
                                RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,
                                RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION);
                        parameterAnnotationInfo[i] = aiList.toArray(new AnnotationInfo[0]);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.typeDescriptor != null) {
            this.typeDescriptor.setScanResult(scanResult);
        }
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
        if (this.annotationInfo != null) {
            for (final AnnotationInfo ai : this.annotationInfo) {
                ai.setScanResult(scanResult);
            }
        }
        if (this.parameterAnnotationInfo != null) {
            for (final AnnotationInfo[] pai : this.parameterAnnotationInfo) {
                if (pai != null) {
                    for (final AnnotationInfo ai : pai) {
                        ai.setScanResult(scanResult);
                    }
                }
            }
        }
        if (this.parameterInfo != null) {
            for (final MethodParameterInfo mpi : parameterInfo) {
                mpi.setScanResult(scanResult);
            }
        }
        if (this.thrownExceptions != null) {
            for (final ClassInfo thrownException : thrownExceptions) {
                if (thrownException.scanResult == null) { // Prevent infinite loop
                    thrownException.setScanResult(scanResult);
                }
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
            final Set<ClassInfo> refdClassInfo, final LogNode log) {
        try {
            final MethodTypeSignature methodSig = getTypeSignature();
            if (methodSig != null) {
                methodSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Illegal type signature for method " + getClassName() + "." + getName() + ": "
                        + getTypeSignatureStr());
            }
        }
        try {
            final MethodTypeSignature methodDesc = getTypeDescriptor();
            if (methodDesc != null) {
                methodDesc.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Illegal type descriptor for method " + getClassName() + "." + getName() + ": "
                        + getTypeDescriptorStr());
            }
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
        for (final MethodParameterInfo mpi : getParameterInfo()) {
            final AnnotationInfo[] aiArr = mpi.annotationInfo;
            if (aiArr != null) {
                for (final AnnotationInfo ai : aiArr) {
                    ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
                }
            }
        }
        if (thrownExceptionNames != null) {
            final ClassInfoList thrownExceptions = getThrownExceptions();
            if (thrownExceptions != null) {
                for (int i = 0; i < thrownExceptions.size(); i++) {
                    classNameToClassInfo.put(thrownExceptionNames[i], thrownExceptions.get(i));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Test class name, method name and type descriptor for equals().
     *
     * @param obj
     *            the object to compare for equality
     * @return true if equal
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof MethodInfo)) {
            return false;
        }
        final MethodInfo other = (MethodInfo) obj;
        return declaringClassName.equals(other.declaringClassName)
                && typeDescriptorStr.equals(other.typeDescriptorStr) && name.equals(other.name);
    }

    /**
     * Use hashcode of class name, method name and type descriptor.
     *
     * @return the hashcode
     */
    @Override
    public int hashCode() {
        return name.hashCode() + typeDescriptorStr.hashCode() * 11 + declaringClassName.hashCode() * 57;
    }

    /**
     * Sort in order of class name, method name, then type descriptor.
     *
     * @param other
     *            the other {@link MethodInfo} to compare.
     * @return the result of the comparison.
     */
    @Override
    public int compareTo(final MethodInfo other) {
        final int diff0 = declaringClassName.compareTo(other.declaringClassName);
        if (diff0 != 0) {
            return diff0;
        }
        final int diff1 = name.compareTo(other.name);
        if (diff1 != 0) {
            return diff1;
        }
        return typeDescriptorStr.compareTo(other.typeDescriptorStr);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a string representation of the method. Note that constructors are named {@code "<init>"}, and private
     * static class initializer blocks are named {@code "<clinit>"}.
     *
     * @param useSimpleNames
     *            the use simple names
     * @param buf
     *            the buf
     */
    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        final MethodTypeSignature methodType = getTypeSignatureOrTypeDescriptor();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                annotation.toString(useSimpleNames, buf);
            }
        }

        if (modifiers != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.METHOD, isDefault(), buf);
        }

        final List<TypeParameter> typeParameters = methodType.getTypeParameters();
        if (!typeParameters.isEmpty()) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append('<');
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                typeParameters.get(i).toString(useSimpleNames, buf);
            }
            buf.append('>');
        }

        if (!isConstructor()) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            methodType.getResultType().toStringInternal(useSimpleNames, /* annotationsToExclude = */ annotationInfo,
                    buf);
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        if (name != null) {
            buf.append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
        }

        // If at least one param is named, then use placeholder names for unnamed params,
        // otherwise don't show names for any params
        final MethodParameterInfo[] allParamInfo = getParameterInfo();
        boolean hasParamNames = false;
        for (final MethodParameterInfo methodParamInfo : allParamInfo) {
            if (methodParamInfo.getName() != null) {
                hasParamNames = true;
                break;
            }
        }

        // Find varargs param index, if present -- this is, for varargs methods, the last argument that
        // is not a synthetic or mandated parameter (turns out the Java compiler can tack on parameters
        // *after* the varargs parameter, for variable capture with anonymous inner classes -- see #260).
        int varArgsParamIndex = -1;
        if (isVarArgs()) {
            for (int i = allParamInfo.length - 1; i >= 0; --i) {
                final int mods = allParamInfo[i].getModifiers();
                if ((mods & /* synthetic */ 0x1000) == 0 && (mods & /* mandated */ 0x8000) == 0) {
                    final TypeSignature paramType = allParamInfo[i].getTypeSignatureOrTypeDescriptor();
                    if (paramType instanceof ArrayTypeSignature) {
                        varArgsParamIndex = i;
                        break;
                    }
                }
            }
        }

        buf.append('(');
        for (int i = 0, numParams = allParamInfo.length; i < numParams; i++) {
            final MethodParameterInfo paramInfo = allParamInfo[i];
            if (i > 0) {
                buf.append(", ");
            }

            if (paramInfo.annotationInfo != null) {
                for (final AnnotationInfo ai : paramInfo.annotationInfo) {
                    ai.toString(useSimpleNames, buf);
                    buf.append(' ');
                }
            }

            MethodParameterInfo.modifiersToString(paramInfo.getModifiers(), buf);

            final TypeSignature paramTypeSignature = paramInfo.getTypeSignatureOrTypeDescriptor();
            // Param type signature may be null in the case of a `synthetic`, `bridge`, or `mandated` parameter
            // implicitly added to a non-generic method
            if (paramTypeSignature != null) {
                if (i == varArgsParamIndex) {
                    // Show varargs params correctly -- replace last "[]" with "..."
                    if (!(paramTypeSignature instanceof ArrayTypeSignature)) {
                        throw new IllegalArgumentException(
                                "Got non-array type for last parameter of varargs method " + name);
                    }
                    final ArrayTypeSignature arrayType = (ArrayTypeSignature) paramTypeSignature;
                    if (arrayType.getNumDimensions() == 0) {
                        throw new IllegalArgumentException(
                                "Got a zero-dimension array type for last parameter of varargs method " + name);
                    }
                    arrayType.getElementTypeSignature().toString(useSimpleNames, buf);
                    for (int j = 0; j < arrayType.getNumDimensions() - 1; j++) {
                        buf.append("[]");
                    }
                    buf.append("...");
                } else {
                    // Exclude parameter annotations from type annotations at toplevel of type signature,
                    // so that annotation is not listed twice
                    final AnnotationInfoList annotationsToExclude;
                    if (paramInfo.annotationInfo == null || paramInfo.annotationInfo.length == 0) {
                        annotationsToExclude = null;
                    } else {
                        annotationsToExclude = new AnnotationInfoList(paramInfo.annotationInfo.length);
                        annotationsToExclude.addAll(Arrays.asList(paramInfo.annotationInfo));
                    }
                    paramTypeSignature.toStringInternal(useSimpleNames, annotationsToExclude, buf);
                }
            }

            if (hasParamNames) {
                final String paramName = paramInfo.getName();
                if (paramName != null) {
                    if (buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    buf.append(paramName);
                }
            }
        }
        buf.append(')');

        // when throws signature is present, it includes both generic type variables and class names
        if (!methodType.getThrowsSignatures().isEmpty()) {
            buf.append(" throws ");
            for (int i = 0; i < methodType.getThrowsSignatures().size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                methodType.getThrowsSignatures().get(i).toString(useSimpleNames, buf);
            }
        } else {
            if (thrownExceptionNames != null && thrownExceptionNames.length > 0) {
                buf.append(" throws ");
                for (int i = 0; i < thrownExceptionNames.length; i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    buf.append(useSimpleNames ? ClassInfo.getSimpleName(thrownExceptionNames[i])
                            : thrownExceptionNames[i]);
                }
            }
        }
    }
}
