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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends ScanResultObject implements Comparable<MethodInfo> {
    /** Defining class name. */
    transient String className;

    /** Defining class ClassInfo. */
    transient ClassInfo classInfo;

    /** Method name. */
    String methodName;

    /** Method modifiers. */
    int modifiers;

    /** Method annotations. */
    List<AnnotationInfo> annotationInfo;

    /**
     * The JVM-internal type descriptor (missing type parameters, but including types for synthetic and mandated
     * method parameters).
     */
    String typeDescriptorStr;

    /** The parsed type descriptor. */
    transient MethodTypeSignature typeDescriptor;

    /**
     * The type signature (may have type parameter information included, if present and available). Method parameter
     * types are unaligned.
     */
    String typeSignatureStr;

    /** The parsed type signature (or null if none). Method parameter types are unaligned. */
    transient MethodTypeSignature typeSignature;

    /**
     * Unaligned parameter names. These are only produced in JDK8+, and only if the commandline switch `-parameters`
     * is provided at compiletime.
     */
    String[] parameterNames;

    /**
     * Unaligned parameter modifiers. These are only produced in JDK8+, and only if the commandline switch
     * `-parameters` is provided at compiletime.
     */
    int[] parameterModifiers;

    /** Unaligned parameter annotations */
    AnnotationInfo[][] parameterAnnotationInfo;

    /** Aligned method parameter info */
    transient MethodParameterInfo[] methodParameterInfo;

    /** Default constructor for deserialization. */
    MethodInfo() {
    }

    /** Sets back-reference to scan result after scan is complete. */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.annotationInfo != null) {
            for (int i = 0; i < this.annotationInfo.size(); i++) {
                final AnnotationInfo ai = this.annotationInfo.get(i);
                ai.setScanResult(scanResult);
            }
        }
        if (this.parameterAnnotationInfo != null) {
            for (int i = 0; i < this.parameterAnnotationInfo.length; i++) {
                final AnnotationInfo[] pai = this.parameterAnnotationInfo[i];
                if (pai != null) {
                    for (final AnnotationInfo ai : pai) {
                        ai.setScanResult(scanResult);
                    }
                }
            }
        }
    }

    /**
     * @param className
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
     */
    public MethodInfo(final String className, final String methodName,
            final List<AnnotationInfo> methodAnnotationInfo, final int modifiers, final String typeDescriptorStr,
            final String typeSignatureStr, final String[] parameterNames, final int[] parameterModifiers,
            final AnnotationInfo[][] parameterAnnotationInfo) {
        this.className = className;
        this.methodName = methodName;
        this.modifiers = modifiers;
        this.typeDescriptorStr = typeDescriptorStr;
        this.typeSignatureStr = typeSignatureStr;
        this.parameterNames = parameterNames;
        this.parameterModifiers = parameterModifiers;
        this.parameterAnnotationInfo = parameterAnnotationInfo;
        this.annotationInfo = methodAnnotationInfo == null || methodAnnotationInfo.isEmpty() ? null
                : methodAnnotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the modifier bits for the method.
     * 
     * @return The modifier bits for the method.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the method modifiers as a String, e.g. "public static final". For the modifier bits, call
     * {@link #getModifiers()}.
     * 
     * @return The modifiers for the method, as a String.
     */
    public String getModifiersStr() {
        return TypeUtils.modifiersToString(getModifiers(), /* isMethod = */ true);
    }

    /**
     * Get the name of the class this method is part of.
     * 
     * @return The name of the enclosing class.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Returns the name of the method. Note that constructors are named {@code "<init>"}, and private static class
     * initializer blocks are named {@code "<clinit>"}.
     * 
     * @return The name of the method.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the low-level internal type descriptor for the method, e.g. {@code "(Ljava/util/List;)V"}. This is
     * the internal type descriptor used by the JVM, so does not include type parameters (due to type erasure), and
     * does include any synthetic and/or mandated parameters generated by the compiler. See also
     * {@link #getTypeDescriptor()}.
     * 
     * @return The internal type descriptor for the method.
     */
    public String getTypeDescriptorStr() {
        return typeDescriptorStr;
    }

    /**
     * Returns the low-level internal type Signature for the method, e.g.
     * {@code "(Ljava/util/List<Ljava/lang/String;>)V"}. This may or may not include synthetic and/or mandated
     * parameters, depending on the compiler. May be null, if there is no type signature in the classfile. See also
     * {@link #getTypeDescriptorStr()}.
     * 
     * @return The internal type signature for the method, or null if not available.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Returns the parsed type descriptor for the method, which will not include type parameters. If you need
     * generic type parameters, call getTypeSignature() instead.
     * 
     * @return The parsed type descriptor for the method.
     */
    public MethodTypeSignature getTypeDescriptor() {
        if (typeDescriptor == null) {
            try {
                typeDescriptor = MethodTypeSignature.parse(classInfo, typeDescriptorStr);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeDescriptor;
    }

    /**
     * Returns the parsed type signature for the method, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this method, call getTypeDescriptor() instead.
     * 
     * @return The parsed type signature for the method, or null if not available.
     */
    public MethodTypeSignature getTypeSignature() {
        if (typeSignature == null && typeSignatureStr != null) {
            try {
                typeSignature = MethodTypeSignature.parse(classInfo, typeSignatureStr);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Returns the parsed type signature for the method, possibly including type parameters. If the parsed type
     * signature is null, indicating that no type signature information is available for this method, returns the
     * parsed type descriptor instead.
     * 
     * @return The parsed type signature for the method, or if not available, the parsed type descriptor for the
     *         method.
     */
    public MethodTypeSignature getTypeSignatureOrTypeDescriptor() {
        final MethodTypeSignature typeSig = getTypeSignature();
        if (typeSig != null) {
            return typeSig;
        } else {
            return getTypeDescriptor();
        }
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
        return "<init>".equals(methodName);
    }

    /**
     * Returns true if this method is public.
     * 
     * @return True if this method is public.
     */
    public boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    /**
     * Returns true if this method is private.
     * 
     * @return True if this method is private.
     */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /**
     * Returns true if this method is protected.
     * 
     * @return True if this method is protected.
     */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /**
     * Returns true if this method is package-private.
     * 
     * @return True if this method is package-private.
     */
    public boolean isPackagePrivate() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    /**
     * Returns true if this method is static.
     * 
     * @return True if this method is static.
     */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /**
     * Returns true if this method is final.
     * 
     * @return True if this method is final.
     */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
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
        // From: http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
        return (modifiers & 0x0040) != 0;
    }

    /**
     * Returns true if this method is a varargs method.
     * 
     * @return True if this is a varargs method.
     */
    public boolean isVarArgs() {
        // From: http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
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

    // -------------------------------------------------------------------------------------------------------------

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final TypeParameter[] EMPTY_TYPE_PARAMETER_ARRAY = new TypeParameter[0];

    private static final ClassRefOrTypeVariableSignature[] EMPTY_CLASS_TYPE_OR_TYPE_VARIABLE_SIGNATURE_ARRAY //
            = new ClassRefOrTypeVariableSignature[0];

    private static final Class<?>[] EMPTY_CLASS_REF_ARRAY = new Class<?>[0];

    private static final AnnotationInfo[] EMPTY_ANNOTATION_INFO_ARRAY = new AnnotationInfo[0];

    private static String[] toStringArray(final List<?> list) {
        if (list.size() == 0) {
            return EMPTY_STRING_ARRAY;
        } else {
            final String[] stringArray = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                stringArray[i] = list.get(i).toString();
            }
            return stringArray;
        }
    }

    private static Class<?>[] toClassRefs(final List<? extends TypeSignature> typeDescriptors) {
        if (typeDescriptors.size() == 0) {
            return EMPTY_CLASS_REF_ARRAY;
        } else {
            final Class<?>[] classRefArray = new Class<?>[typeDescriptors.size()];
            for (int i = 0; i < typeDescriptors.size(); i++) {
                classRefArray[i] = typeDescriptors.get(i).instantiate();
            }
            return classRefArray;
        }
    }

    private static Class<?>[] toClassRefs(final TypeSignature[] typeDescriptors) {
        if (typeDescriptors.length == 0) {
            return EMPTY_CLASS_REF_ARRAY;
        } else {
            final Class<?>[] classRefArray = new Class<?>[typeDescriptors.length];
            for (int i = 0; i < typeDescriptors.length; i++) {
                classRefArray[i] = typeDescriptors[i].instantiate();
            }
            return classRefArray;
        }
    }

    private static ClassRefOrTypeVariableSignature[] toTypeOrTypeVariableSignatureArray(
            final List<? extends ClassRefOrTypeVariableSignature> typeSignatures) {
        if (typeSignatures.size() == 0) {
            return EMPTY_CLASS_TYPE_OR_TYPE_VARIABLE_SIGNATURE_ARRAY;
        } else {
            return typeSignatures.toArray(new ClassRefOrTypeVariableSignature[typeSignatures.size()]);
        }
    }

    private static TypeParameter[] toTypeParameterArray(final List<? extends TypeParameter> typeParameters) {
        if (typeParameters.size() == 0) {
            return EMPTY_TYPE_PARAMETER_ARRAY;
        } else {
            return typeParameters.toArray(new TypeParameter[typeParameters.size()]);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the available information on method parameters.
     * 
     * @return The {@link MethodParameterInfo} objects for the method parameters, one per parameter.
     */
    public MethodParameterInfo[] getParameterInfo() {
        if (methodParameterInfo == null) {
            // Get params from the type descriptor, and from the type signature if available
            final List<TypeSignature> paramTypeDescriptors = getTypeDescriptor().getParameterTypeSignatures();
            final List<TypeSignature> paramTypeSignatures = getTypeSignature() != null
                    ? getTypeSignature().getParameterTypeSignatures()
                    : null;

            // Figure out the number of params in the alignment (should be num params in type descriptor)
            final int numParams = paramTypeDescriptors.size();
            if (paramTypeSignatures != null && paramTypeSignatures.size() > paramTypeDescriptors.size()) {
                // Should not happen
                throw new RuntimeException(
                        "typeSignatureParamTypes.size() > typeDescriptorParamTypes.size() for method " + className
                                + "." + methodName);
            }

            // Figure out number of other fields that need alignment, and check length for consistency 
            final int otherParamMax = Math.max(parameterNames == null ? 0 : parameterNames.length,
                    Math.max(parameterModifiers == null ? 0 : parameterModifiers.length,
                            parameterAnnotationInfo == null ? 0 : parameterAnnotationInfo.length));
            if (otherParamMax > numParams) {
                // Should not happen
                throw new RuntimeException("Type descriptor for method " + className + "." + methodName
                        + " has insufficient parameters");
            }

            // Kotlin is very inconsistent about the arity of each of the parameter metadata types, see:
            // https://github.com/lukehutch/fast-classpath-scanner/issues/175#issuecomment-363031510
            // As a workaround, we assume that any synthetic / mandated parameters must come first in the
            // parameter list, when the arities don't match, and we right-align the metadata fields.
            // This is probably the safest assumption across JVM languages, even though this convention
            // is by no means the only possibility. (Unfortunately we can't just rely on the modifier
            // bits to find synthetic / mandated parameters, because these bits are not always available,
            // and even when they are, they don't always give the right alignment, at least for Kotlin-
            // generated code).

            String[] paramNamesAligned = null;
            if (parameterNames != null && numParams > 0) {
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
            if (parameterModifiers != null && numParams > 0) {
                if (parameterModifiers.length == numParams) {
                    // No alignment necessary
                    paramModifiersAligned = parameterModifiers;
                } else {
                    // Right-align when not the right length
                    paramModifiersAligned = new int[numParams];
                    for (int i = 0, lenDiff = numParams
                            - parameterModifiers.length; i < parameterModifiers.length; i++) {
                        paramModifiersAligned[lenDiff + i] = parameterModifiers[i];
                    }
                }
            }
            AnnotationInfo[][] paramAnnotationInfoAligned = null;
            if (parameterAnnotationInfo != null && numParams > 0) {
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
            if (paramTypeSignatures != null && numParams > 0) {
                if (paramTypeSignatures.size() == paramTypeDescriptors.size()) {
                    // No alignment necessary
                    paramTypeSignaturesAligned = paramTypeSignatures;
                } else {
                    // Right-align when not the right length
                    paramTypeSignaturesAligned = new ArrayList<>(numParams);
                    for (int i = 0, n = numParams - paramTypeSignatures.size(); i < n; i++) {
                        // Left-pad with nulls
                        paramTypeSignaturesAligned.add(null);
                    }
                    paramTypeSignaturesAligned.addAll(paramTypeSignatures);
                }
            }

            // Generate MethodParameterInfo entries
            methodParameterInfo = new MethodParameterInfo[numParams];
            for (int i = 0; i < numParams; i++) {
                methodParameterInfo[i] = new MethodParameterInfo(
                        paramAnnotationInfoAligned == null ? null : paramAnnotationInfoAligned[i],
                        paramModifiersAligned == null ? 0 : paramModifiersAligned[i], paramTypeDescriptors.get(i),
                        paramTypeSignaturesAligned == null ? null : paramTypeSignaturesAligned.get(i),
                        paramNamesAligned == null ? null : paramNamesAligned[i]);
            }
        }
        return methodParameterInfo;
    }

    /**
     * Get the number of parameters of the method.
     * 
     * @return The number of method parameters, i.e. {@code getParameterInfo().size()}.
     */
    public int getNumParameters() {
        // return getTypeSignature().getParameterTypeSignatures().size();
        return getParameterInfo().length;
    }

    /**
     * Returns the parameter types for the method. If the method has no parameters, returns a zero-sized array.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * <p>
     * Note that this calls Class.forName() on the parameter types, which will cause the class to be loaded, and
     * possibly initialized. If the class is initialized, this can trigger side effects.
     *
     * @return The list of {@code Class<?>} references for the method parameter types.
     * @throws IllegalArgumentException
     *             if the parameter types of the method could not be loaded.
     */
    public Class<?>[] getParameterTypes() throws IllegalArgumentException {
        // Can't instantiate type signatures, since they may have type variables.
        // Therefore, we use type descriptors, which are the result of type erasure.
        final MethodParameterInfo[] parameterInfo = getParameterInfo();
        final TypeSignature[] paramTypeDescriptors = new TypeSignature[parameterInfo.length];
        for (int i = 0; i < parameterInfo.length; i++) {
            final MethodParameterInfo paramInfo = parameterInfo[i];
            final TypeSignature typeDesc = paramInfo.getTypeDescriptor();
            paramTypeDescriptors[i] = typeDesc;
        }
        return toClassRefs(paramTypeDescriptors);
    }

    /**
     * Returns the parameter type signatures for the method. If the method has no parameters, returns a zero-sized
     * array.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter types, as an array of parsed type signatures.
     */
    public TypeSignature[] getParameterTypeSignatures() {
        final MethodParameterInfo[] parameterInfo = getParameterInfo();
        final TypeSignature[] paramTypeSignaturesOrTypeDescriptors = new TypeSignature[parameterInfo.length];
        for (int i = 0; i < parameterInfo.length; i++) {
            final MethodParameterInfo paramInfo = parameterInfo[i];
            final TypeSignature typeSig = paramInfo.getTypeSignature();
            paramTypeSignaturesOrTypeDescriptors[i] = typeSig == null ? paramInfo.getTypeDescriptor() : typeSig;
        }
        return paramTypeSignaturesOrTypeDescriptors;
    }

    /**
     * Returns the method parameter names, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch), otherwise returns null if the parameter names are not known. Note
     * that even when a non-null String array is returned, one or more individual array elements may be null,
     * representing unnamed parameters.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter names, as an array of Strings, or null if parameter names are not available.
     */
    public String[] getParameterNames() {
        final MethodParameterInfo[] parameterInfo = getParameterInfo();
        boolean hasNames = false;
        for (int i = 0; i < parameterInfo.length; i++) {
            if (parameterInfo[i].getName() != null) {
                hasNames = true;
                break;
            }
        }
        if (!hasNames) {
            // No name info
            return null;
        }
        final String[] paramNames = new String[parameterInfo.length];
        for (int i = 0; i < parameterInfo.length; i++) {
            paramNames[i] = parameterInfo[i].getName();
        }
        return paramNames;
    }

    /**
     * Returns the parameter modifiers, if available (only available in classfiles compiled in JDK8 or above using
     * the -parameters commandline switch, or code compiled with Kotlin or some other language), otherwise returns
     * null if the parameter modifiers are not known.
     * 
     * <p>
     * Flag bits:
     *
     * <ul>
     * <li>0x0010 (ACC_FINAL): Indicates that the formal parameter was declared final.
     * <li>0x1000 (ACC_SYNTHETIC): Indicates that the formal parameter was not explicitly or implicitly declared in
     * source code, according to the specification of the language in which the source code was written (JLS §13.1).
     * (The formal parameter is an implementation artifact of the compiler which produced this class file.)
     * <li>0x8000 (ACC_MANDATED): Indicates that the formal parameter was implicitly declared in source code,
     * according to the specification of the language in which the source code was written (JLS §13.1). (The formal
     * parameter is mandated by a language specification, so all compilers for the language must emit it.)
     * </ul>
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter modifiers, as an array of integers, or null if parameter modifiers are not
     *         available.
     */
    public int[] getParameterModifiers() {
        final MethodParameterInfo[] parameterInfo = getParameterInfo();
        boolean hasNames = false;
        for (int i = 0; i < parameterInfo.length; i++) {
            if (parameterInfo[i].getName() != null) {
                hasNames = true;
                break;
            }
        }
        if (!hasNames) {
            // There is no modifier info if there is also no name info
            // (this is needed to distinguish between no info, and all-zero modifiers)
            return null;
        }
        final int[] paramMods = new int[parameterInfo.length];
        for (int i = 0; i < parameterInfo.length; i++) {
            paramMods[i] = parameterInfo[i].getModifiers();
        }
        return paramMods;
    }

    /**
     * Returns the parameter modifiers as a string (e.g. ["final", ""], if available (only available in classfiles
     * compiled in JDK8 or above using the -parameters commandline switch), otherwise returns null if the parameter
     * modifiers are not known.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter modifiers, in string representation, as an array of Strings, or null if the
     *         parameter modifiers are not available.
     */
    public String[] getParameterModifierStrs() {
        final int[] paramModifiers = getParameterModifiers();
        if (paramModifiers == null) {
            return null;
        }
        final String[] paramModifierStrs = new String[paramModifiers.length];
        for (int i = 0; i < paramModifiers.length; i++) {
            paramModifierStrs[i] = TypeUtils.modifiersToString(paramModifiers[i], /* isMethod = */ false);
        }
        return paramModifierStrs;
    }

    /**
     * Returns the annotations on each method parameter (along with any annotation parameters, wrapped in
     * AnnotationInfo objects) if any parameters have annotations, else returns null.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter annotations, as an array of {@link AnnotationInfo} objects, or null if no
     *         parameter annotations are present.
     */
    public AnnotationInfo[][] getParameterAnnotationInfo() {
        final MethodParameterInfo[] parameterInfo = getParameterInfo();
        boolean hasAnnotations = false;
        for (int i = 0; i < parameterInfo.length; i++) {
            final AnnotationInfo[] annInfo = parameterInfo[i].getAnnotationInfo();
            if (annInfo != null && annInfo.length > 0) {
                hasAnnotations = true;
                break;
            }
        }
        if (!hasAnnotations) {
            return null;
        }
        final AnnotationInfo[][] annotationInfo = new AnnotationInfo[parameterInfo.length][];
        for (int i = 0; i < parameterInfo.length; i++) {
            annotationInfo[i] = parameterInfo[i].getAnnotationInfo();
            if (annotationInfo[i] == null) {
                annotationInfo[i] = EMPTY_ANNOTATION_INFO_ARRAY;
            }
        }
        return annotationInfo;
    }

    /**
     * Returns the unique annotation names for annotations on each method parameter, if any parameters have
     * annotations, else returns null.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter annotation names, as an array of Strings, or null if no parameter annotations
     *         are presest.
     */
    public String[][] getParameterAnnotationNames() {
        final AnnotationInfo[][] paramAnnotationInfo = getParameterAnnotationInfo();
        if (paramAnnotationInfo == null) {
            return null;
        }
        final String[][] paramAnnotationNames = new String[paramAnnotationInfo.length][];
        for (int i = 0; i < paramAnnotationInfo.length; i++) {
            paramAnnotationNames[i] = AnnotationInfo.getUniqueAnnotationNamesSorted(paramAnnotationInfo[i]);
        }
        return paramAnnotationNames;
    }

    /**
     * Returns the unique annotation types for annotations on each method parameter, if any parameters have
     * annotations, else returns null. Causes the classloader to load each parameter type's class, if not already
     * loaded.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     * 
     * @return The method parameter annotation types, as an array of Strings, or null if no parameter annotations
     *         are present.
     * @throws IllegalArgumentException
     *             if an exception or error is thrown when loading any of the paramater types.
     */
    public Class<?>[][] getParameterAnnotationTypes() {
        final String[][] paramAnnotationNames = getParameterAnnotationNames();
        if (paramAnnotationNames == null) {
            return null;
        }
        final Class<?>[][] parameterAnnotationTypes = new Class<?>[paramAnnotationNames.length][];
        for (int i = 0; i < paramAnnotationNames.length; i++) {
            parameterAnnotationTypes[i] = new Class<?>[paramAnnotationNames[i].length];
            for (int j = 0; j < paramAnnotationNames[i].length; j++) {
                parameterAnnotationTypes[i][j] = scanResult.classNameToClassRef(paramAnnotationNames[i][j],
                        /* ignoreExceptions = */ false);
            }
        }
        return parameterAnnotationTypes;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the result type signature for the method (with type parameters, if present). If this is a
     * constructor, the returned type will be void.
     * 
     * @return The type signature of the result of this method, or null if type signature information is not
     *         available.
     */
    public TypeSignature getResultTypeSignature() {
        final MethodTypeSignature ts = getTypeSignature();
        if (ts == null) {
            return null;
        } else {
            return ts.getResultType();
        }
    }

    /**
     * Returns the result type descriptor for the method (without any type parameters). If this is a constructor,
     * the returned type will be void.
     * 
     * @return The type descriptor of the result of this method.
     */
    public TypeSignature getResultTypeDescriptor() {
        return getTypeDescriptor().getResultType();
    }

    /**
     * Returns the return type for the method as a Class reference. If this is a constructor, the return type will
     * be void.class. Note that this calls {@code Class.forName()} on the return type, which will cause the class to
     * be loaded, and possibly initialized. If the class is initialized, this can trigger side effects.
     * 
     * @return The result type of the method as a {@code Class<?>} reference.
     * @throws IllegalArgumentException
     *             if the return type for the method could not be loaded.
     */
    public Class<?> getResultType() throws IllegalArgumentException {
        return getResultTypeDescriptor().instantiate();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}.
     * 
     * @return The types of exceptions the method may throw. If the method throws no exceptions, returns a
     *         zero-sized array.
     */
    public ClassRefOrTypeVariableSignature[] getThrowsTypeSignatures() {
        return toTypeOrTypeVariableSignatureArray(getTypeSignature().getThrowsSignatures());
    }

    /**
     * Returns the types of exceptions the method may throw.
     * 
     * @return The types of exceptions the method may throw. If the method throws no exceptions, returns a
     *         zero-sized array.
     */
    public Class<?>[] getThrowsTypes() {
        return toClassRefs(getTypeSignature().getThrowsSignatures());
    }

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}.
     * 
     * @return The types of exceptions the method may throw, as Strings. If the method throws no exceptions, returns
     *         a zero-sized array.
     */
    public String[] getThrowsTypeStrs() {
        return toStringArray(getTypeSignature().getThrowsSignatures());
    }

    /**
     * Returns the type parameters of the method.
     * 
     * @return The type parameters of the method. If the method has no type parameters, returns a zero-sized array.
     */
    public TypeParameter[] getTypeParameters() {
        return toTypeParameterArray(getTypeSignature().getTypeParameters());
    }

    /**
     * Returns the type parameters of the method, in string representation, e.g. {@code ["<X>",
     * "<Y>"]}.
     * 
     * @return The type parameters of the method, in string representation, e.g. {@code ["<X>",
     * "<Y>"]}. If the method has no type parameters, returns a zero-sized array.
     */
    public String[] getTypeParameterStrs() {
        return toStringArray(getTypeSignature().getTypeParameters());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the names of annotations on the method.
     * 
     * @return The names of annotations on this method, or the empty list if none.
     */
    public List<String> getAnnotationNames() {
        return annotationInfo == null ? Collections.<String> emptyList()
                : Arrays.asList(AnnotationInfo.getUniqueAnnotationNamesSorted(annotationInfo));
    }

    /**
     * Returns a list of {@code Class<?>} references for the annotations on this method. Note that this calls
     * Class.forName() on the annotation types, which will cause each annotation class to be loaded. Causes the
     * classloader to load each annotation's class, if not already loaded.
     * 
     * @return a list of {@code Class<?>} references for the annotations on this method, or the empty list if none.
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while loading any of the annotation classes.
     */
    public List<Class<?>> getAnnotationTypes() throws IllegalArgumentException {
        if (annotationInfo == null || annotationInfo.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> annotationClassRefs = new ArrayList<>();
            for (final String annotationName : getAnnotationNames()) {
                annotationClassRefs
                        .add(scanResult.classNameToClassRef(annotationName, /* ignoreExceptions = */ false));
            }
            return annotationClassRefs;
        }
    }

    /**
     * Get a list of annotations on this method, along with any annotation parameter values.
     * 
     * @return a list of annotations on this method, along with any annotation parameter values, wrapped in
     *         {@link AnnotationInfo} objects, or the empty list if none.
     */
    public List<AnnotationInfo> getAnnotationInfo() {
        return annotationInfo == null ? Collections.<AnnotationInfo> emptyList() : annotationInfo;
    }

    /** Test class name, method name and type descriptor for equals(). */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        final MethodInfo other = (MethodInfo) obj;
        return className.equals(other.className) && typeDescriptorStr.equals(other.typeDescriptorStr)
                && methodName.equals(other.methodName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Use hash code of class name, method name and type descriptor. */
    @Override
    public int hashCode() {
        return methodName.hashCode() + typeDescriptorStr.hashCode() * 11 + className.hashCode() * 57;
    }

    /** Sort in order of class name, method name, then type descriptor. */
    @Override
    public int compareTo(final MethodInfo other) {
        final int diff0 = className.compareTo(other.className);
        if (diff0 != 0) {
            return diff0;
        }
        final int diff1 = methodName.compareTo(other.methodName);
        if (diff1 != 0) {
            return diff1;
        }
        return typeDescriptorStr.compareTo(other.typeDescriptorStr);
    }

    /**
     * Get a string representation of the method. Note that constructors are named {@code "<init>"}, and private
     * static class initializer blocks are named {@code "<clinit>"}.
     */
    @Override
    public String toString() {
        final MethodTypeSignature methodType = getTypeSignatureOrTypeDescriptor();

        final StringBuilder buf = new StringBuilder();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                annotation.toString(buf);
            }
        }

        if (modifiers != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, /* isMethod = */ true, buf);
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
                final String typeParamStr = typeParameters.get(i).toString();
                buf.append(typeParamStr);
            }
            buf.append('>');
        }

        if (!isConstructor()) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(methodType.getResultType().toString());
        }

        buf.append(' ');
        if (methodName != null) {
            buf.append(methodName);
        }

        // If at least one param is named, then use placeholder names for unnamed params,
        // otherwise don't show names for any params
        final MethodParameterInfo[] allParamInfo = getParameterInfo();
        boolean hasParamNames = false;
        for (int i = 0, numParams = allParamInfo.length; i < numParams; i++) {
            if (allParamInfo[i].getName() != null) {
                hasParamNames = true;
                break;
            }
        }

        buf.append('(');
        for (int i = 0, numParams = allParamInfo.length; i < numParams; i++) {
            final MethodParameterInfo paramInfo = allParamInfo[i];
            if (i > 0) {
                buf.append(", ");
            }

            final AnnotationInfo[] annInfo = paramInfo.getAnnotationInfo();
            if (annInfo != null) {
                for (int j = 0; j < annInfo.length; j++) {
                    annInfo[j].toString(buf);
                    buf.append(' ');
                }
            }

            final int flag = paramInfo.getModifiers();
            if ((flag & Modifier.FINAL) != 0) {
                buf.append("final ");
            }
            if ((flag & TypeUtils.MODIFIER_SYNTHETIC) != 0) {
                buf.append("synthetic ");
            }
            if ((flag & TypeUtils.MODIFIER_MANDATED) != 0) {
                buf.append("mandated ");
            }

            final TypeSignature paramType = paramInfo.getTypeSignatureOrTypeDescriptor();
            if (isVarArgs() && i == numParams - 1) {
                // Show varargs params correctly
                if (!(paramType instanceof ArrayTypeSignature)) {
                    throw new IllegalArgumentException(
                            "Got non-array type for last parameter of varargs method " + methodName);
                }
                final ArrayTypeSignature arrayType = (ArrayTypeSignature) paramType;
                if (arrayType.getNumArrayDims() == 0) {
                    throw new IllegalArgumentException(
                            "Got a zero-dimension array type for last parameter of varargs method " + methodName);
                }
                // Replace last "[]" with "..."
                buf.append(
                        new ArrayTypeSignature(arrayType.getElementTypeSignature(), arrayType.getNumArrayDims() - 1)
                                .toString());
                buf.append("...");
            } else {
                buf.append(paramType.toString());
            }

            if (hasParamNames) {
                final String paramName = paramInfo.getName();
                if (paramName != null) {
                    buf.append(' ');
                    buf.append(paramName == null ? "_unnamed_param_" + i : paramName);
                }
            }
        }
        buf.append(')');

        if (!methodType.getThrowsSignatures().isEmpty()) {
            buf.append(" throws ");
            for (int i = 0; i < methodType.getThrowsSignatures().size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(methodType.getThrowsSignatures().get(i).toString());
            }
        }
        return buf.toString();
    }
}
