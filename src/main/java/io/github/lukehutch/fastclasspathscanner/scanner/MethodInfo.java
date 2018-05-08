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

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult.InfoObject;
import io.github.lukehutch.fastclasspathscanner.typesignature.ArrayTypeSignature;
import io.github.lukehutch.fastclasspathscanner.typesignature.ClassRefOrTypeVariableSignature;
import io.github.lukehutch.fastclasspathscanner.typesignature.MethodTypeSignature;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeParameter;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeSignature;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends InfoObject implements Comparable<MethodInfo> {
    /** Defining class name. */
    private final String className;

    /** Defining class ClassInfo. */
    ClassInfo classInfo;

    /** Method name. */
    private final String methodName;

    /** Method modifiers. */
    private final int modifiers;

    /** Method annotations. */
    final List<AnnotationInfo> annotationInfo;

    /**
     * The JVM-internal type descriptor (missing type parameters, but including types for synthetic and mandated
     * method parameters).
     */
    private final String typeDescriptorStr;

    /** The parsed type descriptor. */
    private MethodTypeSignature typeDescriptor;

    /**
     * The type signature (may have type parameter information included, if present and available). Method parameter
     * types are unaligned.
     */
    private final String typeSignatureStr;

    /** The parsed type signature (or null if none). Method parameter types are unaligned. */
    private MethodTypeSignature typeSignature;

    /**
     * Unaligned parameter names. These are only produced in JDK8+, and only if the commandline switch `-parameters`
     * is provided at compiletime.
     */
    private final String[] parameterNames;

    /**
     * Unaligned parameter modifiers. These are only produced in JDK8+, and only if the commandline switch
     * `-parameters` is provided at compiletime.
     */
    private final int[] parameterModifiers;

    /** Unaligned parameter annotations */
    final AnnotationInfo[][] parameterAnnotationInfo;

    /** Aligned method parameter info */
    private List<MethodParameterInfo> methodParameterInfo;

    private ScanResult scanResult;

    /** Sets back-reference to scan result after scan is complete. */
    @Override
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
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
        this.annotationInfo = methodAnnotationInfo == null || methodAnnotationInfo.isEmpty()
                ? Collections.<AnnotationInfo> emptyList()
                : methodAnnotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns the access flags of the method. */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the method modifiers as a string, e.g. "public static final". For the modifier bits, call
     * getAccessFlags().
     */
    public String getModifiersStr() {
        return TypeUtils.modifiersToString(getModifiers(), /* isMethod = */ true);
    }

    /** Get the name of the class this method is part of. */
    public String getClassName() {
        return className;
    }

    /**
     * Returns the name of the method. Note that constructors are named {@code "<init>"}, and private static class
     * initializer blocks are named {@code "<clinit>"}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the internal type descriptor for the method, e.g. {@code "(Ljava/util/List;)V"}. This is the internal
     * type descriptor used by the JVM, so does not include type parameters (due to type erasure), and does include
     * any synthetic and/or mandated parameters generated by the compiler. See also {@link #getTypeDescriptor()}.
     */
    public String getTypeDescriptorStr() {
        return typeDescriptorStr;
    }

    /**
     * Returns the internal type Signature for the method, e.g. {@code "(Ljava/util/List<Ljava/lang/String;>)V"}.
     * This may or may not include synthetic and/or mandated parameters, depending on the compiler. May be null, if
     * there is no type signature in the classfile. See also {@link #getTypeDescriptorStr()}.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Returns the type signature for the method, possibly including type parameters. If the type signature is null,
     * indicating that no type signature information is available for this method, returns the type descriptor
     * instead.
     */
    public MethodTypeSignature getTypeSignatureOrTypeDescriptor() {
        final MethodTypeSignature typeSig = getTypeSignature();
        if (typeSig != null) {
            return typeSig;
        } else {
            return getTypeDescriptor();
        }
    }

    /**
     * Returns the type signature for the method, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this method, call getTypeDescriptor() instead.
     */
    public MethodTypeSignature getTypeSignature() {
        if (typeSignature == null && typeSignatureStr != null) {
            typeSignature = MethodTypeSignature.parse(classInfo, typeSignatureStr);
        }
        return typeSignature;
    }

    /**
     * Returns the type descriptor for the method, which will not include type parameters. If you need generic type
     * parameters, call getTypeSignature() instead.
     */
    public MethodTypeSignature getTypeDescriptor() {
        if (typeDescriptor == null) {
            typeDescriptor = MethodTypeSignature.parse(classInfo, typeDescriptorStr);
        }
        return typeDescriptor;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns true if this method is a constructor. Constructors have the method name {@code
     * "<init>"}. This returns false for private static class initializer blocks, which are named
     * {@code "<clinit>"}.
     */
    public boolean isConstructor() {
        return "<init>".equals(methodName);
    }

    /** Returns true if this method is public. */
    public boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    /** Returns true if this method is private. */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /** Returns true if this method is protected. */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /** Returns true if this method is package-private. */
    public boolean isPackagePrivate() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    /** Returns true if this method is static. */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /** Returns true if this method is final. */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
    }

    /** Returns true if this method is synchronized. */
    public boolean isSynchronized() {
        return Modifier.isSynchronized(modifiers);
    }

    /** Returns true if this method is a bridge method. */
    public boolean isBridge() {
        // From: http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
        return (modifiers & 0x0040) != 0;
    }

    /** Returns true if this method is a varargs method. */
    public boolean isVarArgs() {
        // From: http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
        return (modifiers & 0x0080) != 0;
    }

    /** Returns true if this method is a native method. */
    public boolean isNative() {
        return Modifier.isNative(modifiers);
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final TypeSignature[] EMPTY_TYPE_SIGNATURE_ARRAY = new TypeSignature[0];

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

    private static Class<?>[] toClassRefs(final List<? extends TypeSignature> typeSignatures,
            final ScanResult scanResult) {
        if (typeSignatures.size() == 0) {
            return EMPTY_CLASS_REF_ARRAY;
        } else {
            final Class<?>[] classRefArray = new Class<?>[typeSignatures.size()];
            for (int i = 0; i < typeSignatures.size(); i++) {
                classRefArray[i] = typeSignatures.get(i).instantiate(scanResult);
            }
            return classRefArray;
        }
    }

    private static TypeSignature[] toTypeSignatureArray(final List<? extends TypeSignature> typeSignatures) {
        if (typeSignatures.size() == 0) {
            return EMPTY_TYPE_SIGNATURE_ARRAY;
        } else {
            return typeSignatures.toArray(new TypeSignature[typeSignatures.size()]);
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

    /** Get the information on method parameters */
    public List<MethodParameterInfo> getParameterInfo() {
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
            methodParameterInfo = new ArrayList<>(numParams);
            for (int i = 0; i < numParams; i++) {
                methodParameterInfo.add(new MethodParameterInfo(
                        paramAnnotationInfoAligned == null ? null : paramAnnotationInfoAligned[i],
                        paramModifiersAligned == null ? 0 : paramModifiersAligned[i], paramTypeDescriptors.get(i),
                        paramTypeSignaturesAligned == null ? null : paramTypeSignaturesAligned.get(i),
                        paramNamesAligned == null ? null : paramNamesAligned[i]));
            }
        }
        return methodParameterInfo;
    }

    /** Get the number of parameters in the method's type signature. */
    public int getNumParameters() {
        // return getTypeSignature().getParameterTypeSignatures().size();
        return getParameterInfo().size();
    }

    /**
     * Get the type signature for each parameter, or the type descriptor if there is no type signature.
     * 
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     */
    private List<TypeSignature> getParamTypeSignaturesOrTypeDescriptors() {
        final List<MethodParameterInfo> parameterInfo = getParameterInfo();
        final List<TypeSignature> paramTypeSignatures = new ArrayList<>(parameterInfo.size());
        for (int i = 0; i < parameterInfo.size(); i++) {
            final MethodParameterInfo paramInfo = parameterInfo.get(i);
            final TypeSignature typeSig = paramInfo.getTypeSignature();
            paramTypeSignatures.add(typeSig == null ? paramInfo.getTypeDescriptor() : typeSig);
        }
        return paramTypeSignatures;
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
     * @throws IllegalArgumentException
     *             if the parameter types of the method could not be loaded.
     */
    public Class<?>[] getParameterTypes() throws IllegalArgumentException {
        return toClassRefs(getParamTypeSignaturesOrTypeDescriptors(), scanResult);
    }

    /**
     * Returns the parameter types for the method in string representation, e.g. {@code ["int",
     * "List<X>", "com.abc.XYZ"]}. If the method has no parameters, returns a zero-sized array.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     */
    public String[] getParameterTypeStrs() {
        return toStringArray(getParamTypeSignaturesOrTypeDescriptors());
    }

    /**
     * Returns the parameter type signatures for the method. If the method has no parameters, returns a zero-sized
     * array.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     */
    public TypeSignature[] getParameterTypeSignatures() {
        return toTypeSignatureArray(getParamTypeSignaturesOrTypeDescriptors());
    }

    /**
     * Returns the method parameter names, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch), otherwise returns null if the parameter names are not known. Note
     * that even when a non-null String array is returned, one or more individual array elements may be null,
     * representing unnamed parameters.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
     */
    public String[] getParameterNames() {
        final List<MethodParameterInfo> parameterInfo = getParameterInfo();
        boolean hasNames = false;
        for (int i = 0; i < parameterInfo.size(); i++) {
            if (parameterInfo.get(i).getName() != null) {
                hasNames = true;
                break;
            }
        }
        if (!hasNames) {
            // No name info
            return null;
        }
        final String[] paramNames = new String[parameterInfo.size()];
        for (int i = 0; i < parameterInfo.size(); i++) {
            paramNames[i] = parameterInfo.get(i).getName();
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
     */
    public int[] getParameterModifiers() {
        final List<MethodParameterInfo> parameterInfo = getParameterInfo();
        boolean hasNames = false;
        for (int i = 0; i < parameterInfo.size(); i++) {
            if (parameterInfo.get(i).getName() != null) {
                hasNames = true;
                break;
            }
        }
        if (!hasNames) {
            // There is no modifier info if there is also no name info
            // (this is needed to distinguish between no info, and all-zero modifiers)
            return null;
        }
        final int[] paramMods = new int[parameterInfo.size()];
        for (int i = 0; i < parameterInfo.size(); i++) {
            paramMods[i] = parameterInfo.get(i).getModifiers();
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
     */
    public AnnotationInfo[][] getParameterAnnotationInfo() {
        final List<MethodParameterInfo> parameterInfo = getParameterInfo();
        boolean hasAnnotations = false;
        for (int i = 0; i < parameterInfo.size(); i++) {
            final AnnotationInfo[] annInfo = parameterInfo.get(i).getAnnotationInfo();
            if (annInfo != null && annInfo.length > 0) {
                hasAnnotations = true;
                break;
            }
        }
        if (!hasAnnotations) {
            return null;
        }
        final AnnotationInfo[][] annotationInfo = new AnnotationInfo[parameterInfo.size()][];
        for (int i = 0; i < parameterInfo.size(); i++) {
            annotationInfo[i] = parameterInfo.get(i).getAnnotationInfo();
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
     * annotations, else returns null.
     *
     * Note that it is always faster to call {@link #getParameterInfo()} and get the parameter information from the
     * returned list of {@link MethodParameterInfo} objects, since this method calls that to compile its results.
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
                parameterAnnotationTypes[i][j] = scanResult.classNameToClassRef(paramAnnotationNames[i][j]);
            }
        }
        return parameterAnnotationTypes;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the result type signature for the method. If this is a constructor, the returned type will be void.
     */
    public TypeSignature getResultTypeSignature() {
        return getTypeSignature().getResultType();
    }

    /**
     * Returns the result type for the method in string representation, e.g. "char[]". If this is a constructor, the
     * returned type will be "void".
     */
    public String getResultTypeStr() {
        return getResultTypeSignature().toString();
    }

    /**
     * Returns the return type for the method as a Class reference. If this is a constructor, the return type will
     * be void.class. Note that this calls Class.forName() on the return type, which will cause the class to be
     * loaded, and possibly initialized. If the class is initialized, this can trigger side effects.
     *
     * @throws IllegalArgumentException
     *             if the return type for the method could not be loaded.
     */
    public Class<?> getResultType() throws IllegalArgumentException {
        return getResultTypeSignature().instantiate(scanResult);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}. If the method throws no exceptions, returns a zero-sized array.
     */
    public ClassRefOrTypeVariableSignature[] getThrowsTypeSignatures() {
        return toTypeOrTypeVariableSignatureArray(getTypeSignature().getThrowsSignatures());
    }

    /**
     * Returns the types of exceptions the method may throw. If the method throws no exceptions, returns a
     * zero-sized array.
     */
    public Class<?>[] getThrowsTypes() {
        return toClassRefs(getTypeSignature().getThrowsSignatures(), scanResult);
    }

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}. If the method throws no exceptions, returns a zero-sized array.
     */
    public String[] getThrowsTypeStrs() {
        return toStringArray(getTypeSignature().getThrowsSignatures());
    }

    /**
     * Returns the type parameters of the method. If the method has no type parameters, returns a zero-sized array.
     */
    public TypeParameter[] getTypeParameters() {
        return toTypeParameterArray(getTypeSignature().getTypeParameters());
    }

    /**
     * Returns the type parameters of the method, in string representation, e.g. {@code ["<X>",
     * "<Y>"]}. If the method has no type parameters, returns a zero-sized array.
     */
    public String[] getTypeParameterStrs() {
        return toStringArray(getTypeSignature().getTypeParameters());
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns the names of annotations on the method, or the empty list if none. */
    public List<String> getAnnotationNames() {
        return Arrays.asList(AnnotationInfo.getUniqueAnnotationNamesSorted(annotationInfo));
    }

    /**
     * Returns a list of Class<?> references for the annotations on this method, or the empty list if none. Note
     * that this calls Class.forName() on the annotation types, which will cause each annotation class to be loaded.
     *
     * @throws IllegalArgumentException
     *             if the annotation type could not be loaded.
     */
    public List<Class<?>> getAnnotationTypes() throws IllegalArgumentException {
        if (annotationInfo == null || annotationInfo.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> annotationClassRefs = new ArrayList<>();
            for (final String annotationName : getAnnotationNames()) {
                annotationClassRefs.add(scanResult.classNameToClassRef(annotationName));
            }
            return annotationClassRefs;
        }
    }

    /**
     * Get a list of annotations on this method, along with any annotation parameter values, wrapped in
     * AnnotationInfo objects, or the empty list if none.
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
        final List<MethodParameterInfo> allParamInfo = getParameterInfo();
        boolean hasParamNames = false;
        for (int i = 0, numParams = allParamInfo.size(); i < numParams; i++) {
            if (allParamInfo.get(i).getName() != null) {
                hasParamNames = true;
                break;
            }
        }

        buf.append('(');
        for (int i = 0, numParams = allParamInfo.size(); i < numParams; i++) {
            final MethodParameterInfo paramInfo = allParamInfo.get(i);
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
