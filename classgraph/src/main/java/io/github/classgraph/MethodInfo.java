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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.ClassInfo.RelType;
import io.github.classgraph.Classfile.MethodTypeAnnotationDecorator;
import io.github.classgraph.TypeUtils.ModifierType;
import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends ClassMemberInfo implements Comparable<MethodInfo> {
    /** The parsed type descriptor. */
    private @Nullable MethodTypeSignature typeDescriptor;

    /**
     * The parsed type signature (or null if none). Method parameter types are unaligned.
     */
    private @Nullable MethodTypeSignature typeSignature;

    /**
     * Unaligned parameter names. These are only produced in JDK8+, and only if the commandline switch `-parameters`
     * is provided at compiletime.
     */
    private @Nullable String @Nullable [] parameterNames;

    /**
     * Unaligned parameter modifiers. These are only produced in JDK8+, and only if the commandline switch
     * `-parameters` is provided at compiletime.
     */
    private int @Nullable [] parameterModifiers;

    /** Unaligned parameter annotations. */
    AnnotationInfo @Nullable [][] parameterAnnotationInfo;

    /** Aligned method parameter info. */
    private @Nullable List<MethodParameterInfo> parameterInfo;

    /** True if this method has a body. */
    private boolean hasBody;

    /** The minimum line number for the body of this method, or 0 if unknown. */
    private int minLineNum;

    /** The maximum line number for the body of this method, or 0 if unknown. */
    private int maxLineNum;

    /**
     * The type annotation decorators for the {@link MethodTypeSignature} instance.
     */
    private @Nullable List<MethodTypeAnnotationDecorator> typeAnnotationDecorators;

    /** The names of the exceptions thrown by this method, or null if none. */
    private @Nullable List<String> thrownExceptionNames;

    /** The exceptions thrown by this method, as a {@link ClassInfoList}. */
    private @Nullable ClassInfoList thrownExceptions;

    // -------------------------------------------------------------------------------------------------------------

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
     *            The parameter names, or null if not available. Individual entries are null for unnamed parameters.
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
            final @Nullable AnnotationInfoList methodAnnotationInfo, final int modifiers,
            final String typeDescriptorStr, final @Nullable String typeSignatureStr,
            final @Nullable String @Nullable [] parameterNames, final int @Nullable [] parameterModifiers,
            final AnnotationInfo @Nullable [][] parameterAnnotationInfo, final boolean hasBody,
            final int minLineNum, final int maxLineNum,
            final @Nullable List<MethodTypeAnnotationDecorator> methodTypeAnnotationDecorators,
            final String @Nullable [] thrownExceptionNames) {
        super(definingClassName, methodName, modifiers, typeDescriptorStr, typeSignatureStr, methodAnnotationInfo);
        this.parameterNames = parameterNames;
        this.parameterModifiers = parameterModifiers;
        this.parameterAnnotationInfo = parameterAnnotationInfo;
        this.hasBody = hasBody;
        this.minLineNum = minLineNum;
        this.maxLineNum = maxLineNum;
        this.typeAnnotationDecorators = methodTypeAnnotationDecorators;
        this.thrownExceptionNames = thrownExceptionNames == null ? null : List.of(thrownExceptionNames);
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
    public String getModifiersString() {
        final var buf = new StringBuilder();
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
        synchronized (this) {
            if (typeDescriptor == null) {
                try {
                    typeDescriptor = MethodTypeSignature.parse(typeDescriptorStr, declaringClassName);
                    typeDescriptor.setScanResult(this.scanResult);
                    if (typeAnnotationDecorators != null) {
                        // Type annotations index formal parameters starting from the first parameter that was
                        // declared in source code. However, the method type descriptor may begin with extra
                        // implicit (compiler-synthesized) parameters that formal_parameter_index does not count --
                        // e.g. the leading enclosing-instance parameter of a non-static inner class constructor, or
                        // the leading (String name, int ordinal) parameters of an enum constructor. Determine how
                        // many such implicit prefix parameters there are, strip them from the descriptor while
                        // running the decorators so that formal_parameter_index lines up, then restore them. See
                        // also getParameterInfo(), which "right-aligns" parameter metadata for the same reason.
                        // (#897)
                        final var descNumParam = typeDescriptor.getParameterTypeSignatures().size();
                        int numImplicitPrefixParams;
                        final var sig = getTypeSignature();
                        if (sig != null) {
                            // The generic type signature omits implicit prefix parameters, so the difference in
                            // parameter count reveals how many there are (the spec-sanctioned relationship).
                            numImplicitPrefixParams = descNumParam - sig.getParameterTypeSignatures().size();
                        } else {
                            // There is no generic type signature (e.g. a non-generic inner-class or enum
                            // constructor), so determine the number of implicit prefix params structurally.
                            numImplicitPrefixParams = getNumImplicitPrefixParams();
                        }
                        // Clamp to a sane range, in case of a compiler bug or a malformed classfile
                        if (numImplicitPrefixParams < 0) {
                            numImplicitPrefixParams = 0;
                        } else if (numImplicitPrefixParams > descNumParam) {
                            numImplicitPrefixParams = descNumParam;
                        }
                        decorateMethodType(typeDescriptor, typeAnnotationDecorators, numImplicitPrefixParams);
                    }
                } catch (final TypeSignatureParseException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            return typeDescriptor;
        }
    }

    /**
     * Determine the number of implicit (compiler-synthesized) parameters at the start of this method's parameter
     * list that are not counted by the {@code formal_parameter_index} of type annotations. This is used only when
     * there is no generic type signature to compare the descriptor against. Currently handles the two standard Java
     * cases: the leading enclosing-instance parameter of a non-static inner class constructor, and the leading
     * {@code (String name, int ordinal)} parameters of an enum constructor. (Local and anonymous classes may add a
     * varying number of synthetic params, and are deliberately not special-cased here -- any resulting mismatch is
     * handled gracefully by {@link #decorateMethodType}.)
     *
     * @return the number of implicit prefix parameters (0 if none, or if it cannot be determined).
     */
    // #897
    private int getNumImplicitPrefixParams() {
        if ("<init>".equals(name)) {
            final var declaringClassInfo = getClassInfo();
            if (declaringClassInfo != null) {
                if (declaringClassInfo.isEnum()) {
                    // enum constructors have two leading synthetic params: (String name, int ordinal)
                    return 2;
                } else if (declaringClassInfo.isInnerClass() && !declaringClassInfo.isStatic()) {
                    // Non-static inner class constructors have a leading enclosing-instance parameter
                    return 1;
                }
            }
        }
        return 0;
    }

    /**
     * Run the method type annotation decorators on the given parsed method type, temporarily stripping the given
     * number of implicit prefix parameters so that {@code formal_parameter_index} values line up with the
     * source-declared parameters. Any individual type annotation that cannot be matched to a parameter type (e.g.
     * due to compiler-specific parameter indexing, as with Kotlin, local or anonymous classes, or a compiler bug)
     * is skipped rather than being allowed to abort parsing of the whole method type.
     *
     * @param methodType
     *            the parsed method type signature or descriptor to decorate.
     * @param decorators
     *            the type annotation decorators to run.
     * @param numImplicitPrefixParams
     *            the number of implicit prefix parameters to strip while decorating (0 for none).
     */
    // #897
    private void decorateMethodType(final MethodTypeSignature methodType,
            final List<MethodTypeAnnotationDecorator> decorators, final int numImplicitPrefixParams) {
        final var paramSigs = methodType.getParameterTypeSignatures();
        // Take a copy of the implicit prefix params before removing them -- do not use the live view returned by
        // List.subList(), since it would be invalidated by the structural modification of paramSigs below.
        final var implicitPrefixParams = numImplicitPrefixParams <= 0 ? null
                : new ArrayList<>(paramSigs.subList(0, numImplicitPrefixParams));
        for (var i = 0; i < numImplicitPrefixParams; i++) {
            paramSigs.remove(0);
        }
        for (final MethodTypeAnnotationDecorator decorator : decorators) {
            try {
                decorator.decorate(methodType);
            } catch (final IllegalArgumentException e) {
                // Skip a type annotation that cannot be matched to a parameter type, rather than failing to produce
                // the whole method type (best effort). (#897)
            }
        }
        if (implicitPrefixParams != null) {
            for (var i = numImplicitPrefixParams - 1; i >= 0; --i) {
                paramSigs.add(0, implicitPrefixParams.get(i));
            }
        }
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
    public @Nullable MethodTypeSignature getTypeSignature() {
        synchronized (this) {
            if (typeSignature == null && typeSignatureStr != null) {
                try {
                    typeSignature = MethodTypeSignature.parse(typeSignatureStr, declaringClassName);
                    typeSignature.setScanResult(this.scanResult);
                    if (typeAnnotationDecorators != null) {
                        // The generic type signature already omits any implicit prefix parameters, so
                        // formal_parameter_index lines up with it directly (strip 0). (#897)
                        decorateMethodType(typeSignature, typeAnnotationDecorators, 0);
                    }
                } catch (final TypeSignatureParseException e) {
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
        synchronized (this) {
            if (thrownExceptions == null && thrownExceptionNames != null) {
                thrownExceptions = new ClassInfoList(thrownExceptionNames.size());
                for (final String thrownExceptionName : thrownExceptionNames) {
                    final var classInfo = scanResult().getClassInfo(thrownExceptionName);
                    if (classInfo != null) {
                        thrownExceptions.add(classInfo);
                        classInfo.setScanResult(scanResult);
                    }
                }
            }
            return thrownExceptions == null ? ClassInfoList.EMPTY_LIST : unmodifiable(thrownExceptions);
        }
    }

    /**
     * Returns the names of the exceptions thrown by the method, whether or not those exception classes were
     * encountered during the scan. Compare with {@link #getThrownExceptions()}, which only lists the exceptions
     * that were themselves scanned.
     *
     * @return The names of the exceptions thrown by the method, as an unmodifiable list (the list may be empty).
     */
    public List<String> getThrownExceptionNames() {
        return thrownExceptionNames == null ? List.of() : thrownExceptionNames;
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
        final var classInfo = getClassInfo();
        return classInfo != null && classInfo.isInterface() && hasBody;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the available information on method parameters.
     *
     * @return The {@link MethodParameterInfo} objects for the method parameters, one per parameter, as an
     *         unmodifiable list (the list may be empty).
     */
    public List<MethodParameterInfo> getParameterInfo() {
        synchronized (this) {
            if (parameterInfo == null) {
                parameterInfo = buildParameterInfo();
            }
            return parameterInfo;
        }
    }

    /**
     * Align the sources of parameter metadata with each other, then build one {@link MethodParameterInfo} per
     * parameter.
     *
     * @return the {@link MethodParameterInfo} objects for the method parameters, one per parameter, as an
     *         unmodifiable list (the list may be empty).
     */
    private List<MethodParameterInfo> buildParameterInfo() {
        // The four sources of parameter metadata -- the type signature, the type descriptor, the MethodParameters
        // attribute and the parameter annotations -- can disagree on how many parameters a method has, because a
        // compiler may record a parameter it generated in some of them but not the others. JVMS 4.7.9.1 sanctions
        // this: "there is no assurance that the number of formal parameter types in the method signature is the
        // same as the number of parameter descriptors in the method descriptor", the example given being an
        // implicitly declared constructor parameter that appears in the descriptor but not in the signature.
        //
        // In every case seen so far the extra parameters are at the front -- the enclosing instance of an inner
        // class constructor, the name and ordinal of an enum constructor, an implicit parameter in Guava 28.2
        // (#660), and the parameters Kotlin adds, which it records with varying arity in each source (#175, see
        // https://github.com/classgraph/classgraph/issues/175#issuecomment-363031510). So when the arities
        // disagree, the shorter sources are right-aligned against the longest one. The synthetic and mandated
        // modifier bits cannot be used to locate those parameters instead, because the MethodParameters attribute
        // that carries them is optional, and Kotlin does not always set them consistently with the alignment.

        // Get param type signatures from the type signature of the method
        List<TypeSignature> paramTypeSignatures = null;
        final var typeSig = getTypeSignature();
        if (typeSig != null) {
            paramTypeSignatures = typeSig.getParameterTypeSignatures();
        }

        // If there is no type signature (i.e. if this is not a generic method), fall back to the type descriptor
        // (N.B. the type descriptor is basically junk, because the compiler may prepend `synthetic` and/or `bridge`
        // parameters automatically, without providing any modifiers for the method, so that it is impossible to
        // know how many parameters have been prepended -- see #660.)
        List<TypeSignature> paramTypeDescriptors = null;
        try {
            paramTypeDescriptors = getTypeDescriptor().getParameterTypeSignatures();
        } catch (final Exception e) {
            // Ignore any IllegalArgumentExceptions triggered when type annotations are not able to be aligned with
            // parameters, when there is a `synthetic`, `bridge` or `mandated` parameter added to the first
            // parameter position.
        }

        // Find the max length of all the parameter information sources
        var numParams = paramTypeSignatures == null ? 0 : paramTypeSignatures.size();
        if (paramTypeDescriptors != null) {
            numParams = Math.max(numParams, paramTypeDescriptors.size());
        }
        if (parameterNames != null) {
            numParams = Math.max(numParams, parameterNames.length);
        }
        if (parameterModifiers != null) {
            numParams = Math.max(numParams, parameterModifiers.length);
        }
        if (parameterAnnotationInfo != null) {
            numParams = Math.max(numParams, parameterAnnotationInfo.length);
        }

        // Right-align all the parameter information sources against the longest of them
        final var paramNamesAligned = rightAlign(parameterNames, numParams);
        final var paramModifiersAligned = rightAlign(parameterModifiers, numParams);
        final var paramAnnotationInfoAligned = rightAlign(parameterAnnotationInfo, numParams);
        final var paramTypeSignaturesAligned = rightAlign(paramTypeSignatures, numParams);
        final var paramTypeDescriptorsAligned = rightAlign(paramTypeDescriptors, numParams);

        // Generate MethodParameterInfo entries
        final var paramInfoArr = new MethodParameterInfo[numParams];
        for (var i = 0; i < numParams; i++) {
            paramInfoArr[i] = new MethodParameterInfo(this, i,
                    paramAnnotationInfoAligned == null ? null : paramAnnotationInfoAligned[i],
                    paramModifiersAligned == null ? 0 : paramModifiersAligned[i],
                    paramTypeDescriptorsAligned == null ? null : paramTypeDescriptorsAligned.get(i),
                    paramTypeSignaturesAligned == null ? null : paramTypeSignaturesAligned.get(i),
                    paramNamesAligned == null ? null : paramNamesAligned[i]);
            paramInfoArr[i].setScanResult(scanResult);
        }
        return List.of(paramInfoArr);
    }

    /**
     * Right-align one source of parameter metadata against the parameter count, i.e. assume that any implicit
     * parameters the compiler added were added at the beginning of the parameter list, not the end. The padding
     * added at the beginning consists of null entries.
     *
     * @param values
     *            the values, one per parameter, or null if this source of parameter metadata is not available.
     *            There are never more values than parameters.
     * @param numParams
     *            the number of parameters.
     * @return the right-aligned values, or null if {@code values} is null or empty.
     */
    private static <T extends @Nullable Object> T @Nullable [] rightAlign(final T @Nullable [] values,
            final int numParams) {
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length == numParams) {
            // No alignment necessary
            return values;
        }
        final var lenDiff = numParams - values.length;
        final var aligned = Arrays.copyOf(values, numParams);
        System.arraycopy(values, 0, aligned, lenDiff, values.length);
        Arrays.fill(aligned, 0, lenDiff, null);
        return aligned;
    }

    /**
     * Right-align the parameter modifiers against the parameter count. The padding added at the beginning consists
     * of zero modifier bits.
     *
     * @param values
     *            the modifiers, one per parameter, or null if the {@code MethodParameters} attribute is not
     *            present. There are never more values than parameters.
     * @param numParams
     *            the number of parameters.
     * @return the right-aligned modifiers, or null if {@code values} is null or empty.
     */
    private static int @Nullable [] rightAlign(final int @Nullable [] values, final int numParams) {
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length == numParams) {
            // No alignment necessary
            return values;
        }
        final var aligned = new int[numParams];
        System.arraycopy(values, 0, aligned, numParams - values.length, values.length);
        return aligned;
    }

    /**
     * Right-align a list of parameter types against the parameter count. The padding added at the beginning
     * consists of null entries.
     *
     * @param values
     *            the types, one per parameter, or null if this source of parameter metadata is not available. There
     *            are never more values than parameters.
     * @param numParams
     *            the number of parameters.
     * @return the right-aligned types, or null if {@code values} is null or empty.
     */
    private static @Nullable List<TypeSignature> rightAlign(final @Nullable List<TypeSignature> values,
            final int numParams) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() == numParams) {
            // No alignment necessary
            return values;
        }
        final List<TypeSignature> aligned = new ArrayList<>(numParams);
        for (var i = values.size(); i < numParams; i++) {
            // Left-pad with nulls
            aligned.add(null);
        }
        aligned.addAll(values);
        return aligned;
    }

    /**
     * Get the index of the variadic parameter of this method, i.e. the parameter declared as {@code T...}.
     *
     * @return The index of the variadic parameter within {@link #getParameterInfo()}, or -1 if this method is not
     *         variadic.
     */
    int getVarArgsParamIndex() {
        if (!isVarArgs()) {
            return -1;
        }
        // The variadic parameter is the last parameter that is not synthetic or mandated -- the Java compiler can
        // tack on parameters *after* the variadic parameter, for variable capture with anonymous inner classes
        // (see #260)
        final var allParamInfo = getParameterInfo();
        for (var i = allParamInfo.size() - 1; i >= 0; --i) {
            final var paramInfo = allParamInfo.get(i);
            final var mods = paramInfo.getModifiers();
            if ((mods & /* synthetic */ 0x1000) == 0 && (mods & /* mandated */ 0x8000) == 0
                    && paramInfo.getTypeSignatureOrTypeDescriptor() instanceof ArrayTypeSignature) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check if this method has a parameter with the annotation.
     *
     * @param annotation
     *            The method parameter annotation.
     * @return true if this method has a parameter with the annotation.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public boolean hasParameterAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return hasParameterAnnotation(annotation.getName());
    }

    /**
     * Check if this method has a parameter with the named annotation.
     *
     * @param annotationName
     *            The name of a method parameter annotation.
     * @return true if this method has a parameter with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public boolean hasParameterAnnotation(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        for (final MethodParameterInfo methodParameterInfo : getParameterInfo()) {
            if (methodParameterInfo.hasAnnotation(annotationName)) {
                return true;
            }
        }
        return false;
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
            for (var i = 0; i < parameterAnnotationInfo.length; i++) {
                final var pai = parameterAnnotationInfo[i];
                if (pai != null && pai.length > 0) {
                    var hasRepeatableAnnotation = false;
                    for (final AnnotationInfo ai : pai) {
                        if (allRepeatableAnnotationNames.contains(ai.getName())) {
                            hasRepeatableAnnotation = true;
                            break;
                        }
                    }
                    if (hasRepeatableAnnotation) {
                        final var aiList = new AnnotationInfoList(pai.length);
                        aiList.addAll(Arrays.asList(pai));
                        aiList.handleRepeatableAnnotations(allRepeatableAnnotationNames, getClassInfo(),
                                RelType.METHOD_PARAMETER_ANNOTATIONS,
                                RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,
                                RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION);
                        parameterAnnotationInfo[i] = aiList.toArray(AnnotationInfo[]::new);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
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
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        try {
            final var methodSig = getTypeSignature();
            if (methodSig != null) {
                methodSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Illegal type signature for method " + getClassName() + "." + getName() + ": "
                        + getTypeSignatureString());
            }
        }
        try {
            getTypeDescriptor().findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Illegal type descriptor for method " + getClassName() + "." + getName() + ": "
                        + getTypeDescriptorString());
            }
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
        for (final MethodParameterInfo mpi : getParameterInfo()) {
            final var aiArr = mpi.annotationInfo;
            if (aiArr != null) {
                for (final AnnotationInfo ai : aiArr) {
                    ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
                }
            }
        }
        if (thrownExceptionNames != null) {
            // The exceptions in the throws clause are dependencies of the declaring class. (Resolving them also
            // gives their ClassInfo objects a backref to the ScanResult.) N.B. any exception types in the generic
            // method type signature were already added above, but the throws clause of a non-generic method is only
            // recorded in the "Exceptions" attribute of the method.
            refdClassInfo.addAll(getThrownExceptions());
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
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final MethodInfo other)) {
            return false;
        }
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
        final var diff0 = declaringClassName.compareTo(other.declaringClassName);
        if (diff0 != 0) {
            return diff0;
        }
        final var diff1 = name.compareTo(other.name);
        if (diff1 != 0) {
            return diff1;
        }
        return typeDescriptorStr.compareTo(other.typeDescriptorStr);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a string representation of the method, in Java source syntax. A constructor is named after the class it
     * constructs, as {@link java.lang.reflect.Constructor#toString()} names it, rather than {@code "<init>"} (which
     * is what {@link #getName()} returns). Static class initializer blocks are named {@code "<clinit>"}.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        final var methodType = getTypeSignatureOrTypeDescriptor();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (!buf.isEmpty()) {
                    buf.append(' ');
                }
                annotation.toString(useSimpleNames, buf);
            }
        }

        if (modifiers != 0) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.METHOD, isDefault(), buf);
        }

        final var typeParameters = methodType.getTypeParameters();
        if (!typeParameters.isEmpty()) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append('<');
            for (var i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                typeParameters.get(i).toString(useSimpleNames, buf);
            }
            buf.append('>');
        }

        final var isConstructor = isConstructor();
        if (!isConstructor) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            methodType.getResultType().toStringInternal(useSimpleNames, /* annotationsToExclude = */ annotationInfo,
                    buf);
        }

        if (!buf.isEmpty()) {
            buf.append(' ');
        }
        // Constructors are named "<init>" in the classfile, but Constructor::toString names a constructor after the
        // class it constructs, which is also the Java source syntax
        final var displayedName = isConstructor ? declaringClassName : name;
        buf.append(useSimpleNames ? ClassInfo.getSimpleName(displayedName) : displayedName);

        toStringParameterList(useSimpleNames, buf);

        toStringThrowsClause(methodType, useSimpleNames, buf);
    }

    /**
     * Append the parenthesized parameter list of the method to the buffer.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    private void toStringParameterList(final boolean useSimpleNames, final StringBuilder buf) {
        // If at least one param is named, then use placeholder names for unnamed params, otherwise don't show names
        // for any params
        final var allParamInfo = getParameterInfo();
        var hasParamNames = false;
        for (final MethodParameterInfo methodParamInfo : allParamInfo) {
            if (methodParamInfo.getName() != null) {
                hasParamNames = true;
                break;
            }
        }

        final var varArgsParamIndex = getVarArgsParamIndex();

        buf.append('(');
        for (int i = 0, numParams = allParamInfo.size(); i < numParams; i++) {
            final var paramInfo = allParamInfo.get(i);
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

            final var paramTypeSignature = paramInfo.getTypeSignatureOrTypeDescriptor();
            // Param type signature may be null in the case of a `synthetic`, `bridge`, or `mandated` parameter
            // implicitly added to a non-generic method
            if (paramTypeSignature != null) {
                // Exclude parameter annotations from type annotations at toplevel of type signature, so that
                // annotation is not listed twice
                final AnnotationInfoList annotationsToExclude;
                if (paramInfo.annotationInfo == null || paramInfo.annotationInfo.length == 0) {
                    annotationsToExclude = null;
                } else {
                    annotationsToExclude = new AnnotationInfoList(paramInfo.annotationInfo.length);
                    annotationsToExclude.addAll(Arrays.asList(paramInfo.annotationInfo));
                }
                // The variadic parameter of a variadic method has an array type, but is shown as "T..." not "T[]"
                if (i == varArgsParamIndex && paramTypeSignature instanceof final ArrayTypeSignature arrayType) {
                    arrayType.toStringVarArgs(useSimpleNames, annotationsToExclude, buf);
                } else {
                    paramTypeSignature.toStringInternal(useSimpleNames, annotationsToExclude, buf);
                }
            }

            if (hasParamNames) {
                final var paramName = paramInfo.getName();
                if (paramName != null) {
                    if (buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    buf.append(paramName);
                }
            }
        }
        buf.append(')');
    }

    /**
     * Append the {@code throws} clause of the method to the buffer, if the method declares any thrown exceptions.
     *
     * @param methodType
     *            the type signature of the method, or its type descriptor if it has no type signature
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    private void toStringThrowsClause(final MethodTypeSignature methodType, final boolean useSimpleNames,
            final StringBuilder buf) {
        // When the throws signature is present, it includes both generic type variables and class names
        final var throwsSignatures = methodType.getThrowsSignatures();
        if (!throwsSignatures.isEmpty()) {
            buf.append(" throws ");
            for (var i = 0; i < throwsSignatures.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                throwsSignatures.get(i).toString(useSimpleNames, buf);
            }
        } else if (thrownExceptionNames != null && !thrownExceptionNames.isEmpty()) {
            buf.append(" throws ");
            for (var i = 0; i < thrownExceptionNames.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final var thrownExceptionName = thrownExceptionNames.get(i);
                buf.append(useSimpleNames ? ClassInfo.getSimpleName(thrownExceptionName) : thrownExceptionName);
            }
        }
    }
}
