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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult.InfoObject;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser.ClassTypeOrTypeVariableSignature;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser.MethodSignature;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser.TypeParameter;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser.TypeSignature;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends InfoObject implements Comparable<MethodInfo> {
    private final String className;
    private final String methodName;
    private final int modifiers;
    /**
     * The JVM-internal type descriptor (missing type parameters, but including synthetic and mandated parameters)
     */
    private final String typeDescriptorInternal;
    private MethodSignature methodSignatureInternal;
    /**
     * The human-readable type descriptor (may have type parameter information included, and does not include
     * synthetic or mandated parameters)
     */
    private final String typeDescriptorHumanReadable;
    private MethodSignature methodSignatureHumanReadable;

    private final String[] parameterNames;
    private final int[] parameterAccessFlagsInternal;
    private int[] parameterAccessFlags;
    final AnnotationInfo[][] parameterAnnotationInfo;
    final List<AnnotationInfo> annotationInfo;
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

    public MethodInfo(final String className, final String methodName, final int modifiers,
            final String typeDescriptorInternal, final String typeDescriptorHumanReadable,
            final String[] parameterNames, final int[] parameterAccessFlagsInternal,
            final List<AnnotationInfo> methodAnnotationInfo, final AnnotationInfo[][] parameterAnnotationInfo) {
        this.className = className;
        this.methodName = methodName;
        this.modifiers = modifiers;
        this.typeDescriptorInternal = typeDescriptorInternal;
        this.typeDescriptorHumanReadable = typeDescriptorHumanReadable;
        this.parameterNames = parameterNames;
        this.parameterAccessFlagsInternal = parameterAccessFlagsInternal;
        this.parameterAnnotationInfo = parameterAnnotationInfo;
        this.annotationInfo = methodAnnotationInfo == null || methodAnnotationInfo.isEmpty()
                ? Collections.<AnnotationInfo> emptyList()
                : methodAnnotationInfo;
    }

    /**
     * Get the method modifiers as a string, e.g. "public static final". For the modifier bits, call
     * getAccessFlags().
     */
    // TODO: Rename to getModifiersStr()
    public String getModifiers() {
        return TypeParser.modifiersToString(getAccessFlags(), /* isMethod = */ true);
    }

    /**
     * Returns true if this method is a constructor. Constructors have the method name {@code
     * "<init>"}. This returns false for private static class initializer blocks, which are named
     * {@code "<clinit>"}.
     */
    public boolean isConstructor() {
        return "<init>".equals(methodName);
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

    /** Returns the access flags of the method. */
    // TODO: Rename to getModifiers()
    public int getAccessFlags() {
        return modifiers;
    }

    /**
     * Returns the type descriptor for the method, e.g. "(Ljava/lang/String;)V". This is a machine-readable type
     * string, but it presents the programmer's view of the method type, in the sense that type parameters are
     * included if they are available, and compiler-generated synthetic or mandated parameters are not included. See
     * also {@link getTypeDescriptorInternal()}.
     */
    public String getTypeDescriptor() {
        return typeDescriptorHumanReadable;
    }

    /**
     * Returns the internal type descriptor for the method, e.g. "(Ljava/lang/String;)V". This is the internal type
     * descriptor used by the JVM, so does not include type parameters (due to type erasure), but does include any
     * synthetic and/or mandated parameters generated by the compiler. See also {@link getTypeDescriptor()}.
     */
    public String getTypeDescriptorInternal() {
        return typeDescriptorInternal;
    }

    /**
     * Returns the Java type signature for the method. This is the programmer-visible type signature, in the sense
     * that type parameters are included if they are available, and synthetic and mandated parameters are not
     * included. See also {@link getTypeSignatureInternal()} and {@link getTypeSignatureUnified()}.
     */
    public MethodSignature getTypeSignature() {
        if (typeDescriptorHumanReadable == null) {
            return getTypeSignatureInternal();
        } else {
            if (methodSignatureHumanReadable == null) {
                methodSignatureHumanReadable = TypeParser.parseMethodSignature(typeDescriptorHumanReadable);
            }
            return methodSignatureHumanReadable;
        }
    }

    /**
     * Returns the internal Java type signature for the method. This is the internal type signature used by the JVM,
     * so does not include type parameters (due to type erasure), but does include any synthetic and/or mandated
     * parameters generated by the compiler. See also {@link getTypeSignature()} and
     * {@link getTypeSignatureUnified()}.
     */
    public MethodSignature getTypeSignatureInternal() {
        if (methodSignatureInternal == null) {
            methodSignatureInternal = TypeParser.parseMethodSignature(typeDescriptorInternal);
        }
        return methodSignatureInternal;
    }

    /**
     * Returns the unification of type signatures returned by and {@link getTypeSignature()} and and
     * {@link getTypeSignatureInternal()}, i.e. returns all known type signature information, including any
     * available type parameters, and also any synthetic and/or mandated parameters.
     */
    public MethodSignature getTypeSignatureUnified() {
        if (getTypeSignature() == null) {
            return getTypeSignatureInternal();
        } else if (getTypeSignatureInternal() == null) {
            return getTypeSignature();
        } else {
            return TypeParser.merge(getTypeSignature(), getTypeSignatureInternal(),
                    getParameterAccessFlagsInternal());
        }
    }

    /**
     * Returns the return type signature for the method. If this is a constructor, the returned type will be void.
     */
    public TypeSignature getReturnTypeSignature() {
        return getTypeSignature().resultType;
    }

    /**
     * Returns the return type for the method in string representation, e.g. "char[]". If this is a constructor, the
     * returned type will be "void".
     */
    public String getReturnTypeStr() {
        return getReturnTypeSignature().toString();
    }

    /**
     * Returns the return type for the method as a Class reference. If this is a constructor, the return type will
     * be void.class. Note that this calls Class.forName() on the return type, which will cause the class to be
     * loaded, and possibly initialized. If the class is initialized, this can trigger side effects.
     *
     * @throws IllegalArgumentException
     *             if the return type for the method could not be loaded.
     */
    public Class<?> getReturnType() throws IllegalArgumentException {
        return getReturnTypeSignature().instantiate(scanResult);
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final TypeSignature[] EMPTY_TYPE_SIGNATURE_ARRAY = new TypeSignature[0];

    private static final TypeParameter[] EMPTY_TYPE_PARAMETER_ARRAY = new TypeParameter[0];

    private static final ClassTypeOrTypeVariableSignature[] EMPTY_CLASS_TYPE_OR_TYPE_VARIABLE_SIGNATURE_ARRAY //
            = new ClassTypeOrTypeVariableSignature[0];

    private static final Class<?>[] EMPTY_CLASS_REF_ARRAY = new Class<?>[0];

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

    private static ClassTypeOrTypeVariableSignature[] toTypeOrTypeVariableSignatureArray(
            final List<? extends ClassTypeOrTypeVariableSignature> typeSignatures) {
        if (typeSignatures.size() == 0) {
            return EMPTY_CLASS_TYPE_OR_TYPE_VARIABLE_SIGNATURE_ARRAY;
        } else {
            return typeSignatures.toArray(new ClassTypeOrTypeVariableSignature[typeSignatures.size()]);
        }
    }

    private static TypeParameter[] toTypeParameterArray(final List<? extends TypeParameter> typeParameters) {
        if (typeParameters.size() == 0) {
            return EMPTY_TYPE_PARAMETER_ARRAY;
        } else {
            return typeParameters.toArray(new TypeParameter[typeParameters.size()]);
        }
    }

    /**
     * Returns the parameter type signatures for the method. If the method has no parameters, returns a zero-sized
     * array.
     */
    public TypeSignature[] getParameterTypeSignatures() {
        return toTypeSignatureArray(getTypeSignature().paramTypes);
    }

    /**
     * Returns the parameter types for the method. If the method has no parameters, returns a zero-sized array.
     *
     * <p>
     * Note that this calls Class.forName() on the parameter types, which will cause the class to be loaded, and
     * possibly initialized. If the class is initialized, this can trigger side effects.
     *
     * @throws IllegalArgumentException
     *             if the parameter types of the method could not be loaded.
     */
    public Class<?>[] getParameterTypes() throws IllegalArgumentException {
        return toClassRefs(getTypeSignature().paramTypes, scanResult);
    }

    /**
     * Returns the parameter types for the method in string representation, e.g. {@code ["int",
     * "List<X>", "com.abc.XYZ"]}. If the method has no parameters, returns a zero-sized array.
     */
    public String[] getParameterTypeStrs() {
        return toStringArray(getTypeSignature().paramTypes);
    }

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}. If the method throws no exceptions, returns a zero-sized array.
     */
    public ClassTypeOrTypeVariableSignature[] getThrowsTypeSignatures() {
        return toTypeOrTypeVariableSignatureArray(getTypeSignature().throwsSignatures);
    }

    /**
     * Returns the types of exceptions the method may throw. If the method throws no exceptions, returns a
     * zero-sized array.
     */
    public Class<?>[] getThrowsTypes() {
        return toClassRefs(getTypeSignature().throwsSignatures, scanResult);
    }

    /**
     * Returns the types of exceptions the method may throw, in string representation, e.g. {@code
     * ["com.abc.BadException", "<X>"]}. If the method throws no exceptions, returns a zero-sized array.
     */
    public String[] getThrowsTypeStrs() {
        return toStringArray(getTypeSignature().throwsSignatures);
    }

    /**
     * Returns the type parameters of the method. If the method has no type parameters, returns a zero-sized array.
     */
    public TypeParameter[] getTypeParameters() {
        return toTypeParameterArray(getTypeSignature().typeParameters);
    }

    /**
     * Returns the type parameters of the method, in string representation, e.g. {@code ["<X>",
     * "<Y>"]}. If the method has no type parameters, returns a zero-sized array.
     */
    public String[] getTypeParameterStrs() {
        return toStringArray(getTypeSignature().typeParameters);
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

    /**
     * Returns the method parameter names, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch), otherwise returns null.
     *
     * <p>
     * Note that parameters may be unnamed, in which case the corresponding parameter name will be null.
     */
    public String[] getParameterNames() {
        return parameterNames;
    }

    /**
     * Returns the parameter access flags, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch, or code compiled with Kotlin or some other language), otherwise
     * returns null.
     * 
     * This method returns only access flags for non-synthetic, non-mandated methods (i.e. programmer-visible
     * methods). Consequently, the only flag bit used is 0x0010 (ACC_FINAL): Indicates that the formal parameter was
     * declared final.
     * 
     * Compare to {@link getParameterAccessFlagsInternal()}.
     */
    // TODO: Rename to getParameterModifiers()
    public int[] getParameterAccessFlags() {
        if (parameterAccessFlagsInternal == null) {
            return null;
        } else if (parameterAccessFlags == null) {
            // Copy modifiers only for entries that are non-synthetic, non-mandated
            int numNonSyntheticOrMandated = 0;
            for (int i = 0; i < parameterAccessFlagsInternal.length; i++) {
                if ((parameterAccessFlagsInternal[i] & 0x9000) == 0) {
                    numNonSyntheticOrMandated++;
                }
            }
            parameterAccessFlags = new int[numNonSyntheticOrMandated];
            for (int i = 0, j = 0; i < parameterAccessFlagsInternal.length; i++) {
                if ((parameterAccessFlagsInternal[i] & 0x9000) == 0) {
                    parameterAccessFlags[j++] = parameterAccessFlagsInternal[i];
                }
            }
        }
        return parameterAccessFlags;
    }

    /**
     * Returns the parameter access flags, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch, or code compiled with Kotlin or some other language), otherwise
     * returns null.
     * 
     * This method returns modifiers for all JVM-visible parameters, including synthetic and mandated parameters.
     * Compare to {@link getParameterAccessFlags()}.
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
     */
    // TODO: Rename to getParameterModifiersInternal()
    public int[] getParameterAccessFlagsInternal() {
        return parameterAccessFlagsInternal;
    }

    /**
     * Returns the parameter modifiers as a string (e.g. ["final", ""], if available (only available in classfiles
     * compiled in JDK8 or above using the -parameters commandline switch), otherwise returns null.
     */
    // TODO: Rename to getParameterModifierStrs()
    public String[] getParameterModifiers() {
        if (parameterAccessFlagsInternal == null) {
            return null;
        }
        final String[] parameterModifierStrs = new String[parameterAccessFlagsInternal.length];
        for (int i = 0; i < parameterAccessFlagsInternal.length; i++) {
            parameterModifierStrs[i] = TypeParser.modifiersToString(parameterAccessFlagsInternal[i],
                    /* isMethod = */ false);
        }
        return parameterModifierStrs;
    }

    /**
     * Returns the unique annotation names for annotations on each method parameter, if any parameters have
     * annotations, else returns null.
     */
    public String[][] getParameterAnnotationNames() {
        if (parameterAnnotationInfo == null) {
            return null;
        }
        final String[][] parameterAnnotationNames = new String[parameterAnnotationInfo.length][];
        for (int i = 0; i < parameterAnnotationInfo.length; i++) {
            parameterAnnotationNames[i] = AnnotationInfo.getUniqueAnnotationNamesSorted(parameterAnnotationInfo[i]);
        }
        return parameterAnnotationNames;
    }

    /**
     * Returns the unique annotation types for annotations on each method parameter, if any parameters have
     * annotations, else returns null.
     */
    public Class<?>[][] getParameterAnnotationTypes() {
        if (parameterAnnotationInfo == null) {
            return null;
        }
        final String[][] parameterAnnotationNames = getParameterAnnotationNames();
        final Class<?>[][] parameterAnnotationTypes = new Class<?>[parameterAnnotationNames.length][];
        for (int i = 0; i < parameterAnnotationInfo.length; i++) {
            parameterAnnotationTypes[i] = new Class<?>[parameterAnnotationNames[i].length];
            for (int j = 0; j < parameterAnnotationNames[i].length; j++) {
                parameterAnnotationTypes[i][j] = scanResult.classNameToClassRef(parameterAnnotationNames[i][j]);
            }
        }
        return parameterAnnotationTypes;
    }

    /**
     * Returns the annotations on each method parameter (along with any annotation parameters, wrapped in
     * AnnotationInfo objects) if any parameters have annotations, else returns null.
     */
    public AnnotationInfo[][] getParameterAnnotationInfo() {
        return parameterAnnotationInfo;
    }

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
        return className.equals(other.className) && typeDescriptorInternal.equals(other.typeDescriptorInternal)
                && methodName.equals(other.methodName);
    }

    /** Use hash code of class name, method name and type descriptor. */
    @Override
    public int hashCode() {
        return methodName.hashCode() + typeDescriptorInternal.hashCode() * 11 + className.hashCode() * 57;
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
        return typeDescriptorInternal.compareTo(other.typeDescriptorInternal);
    }

    /**
     * Get a string representation of the method. Note that constructors are named {@code "<init>"}, and private
     * static class initializer blocks are named {@code "<clinit>"}.
     */
    @Override
    public String toString() {
        return getTypeSignatureUnified().toString(annotationInfo, modifiers, isConstructor(), methodName,
                isVarArgs(), parameterNames, parameterAccessFlagsInternal, parameterAnnotationInfo);
    }
}
