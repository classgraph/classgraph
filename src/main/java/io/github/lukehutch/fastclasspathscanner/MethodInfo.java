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
package io.github.lukehutch.fastclasspathscanner;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends ScanResultObject implements Comparable<MethodInfo> {
    /** Defining class name. */
    String definingClassName;

    /** Method name. */
    String name;

    /** Method modifiers. */
    int modifiers;

    /** Method annotations. */
    AnnotationInfoList annotationInfo;

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
    transient MethodParameterInfo[] parameterInfo;

    /** Default constructor for deserialization. */
    MethodInfo() {
    }

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
        if (this.parameterInfo != null) {
            for (final MethodParameterInfo mpi : parameterInfo) {
                mpi.scanResult = scanResult;
            }
        }
    }

    /**
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
     * @param scanSpec
     *            The {@link ScanSpec}.
     */
    MethodInfo(final String definingClassName, final String methodName,
            final AnnotationInfoList methodAnnotationInfo, final int modifiers, final String typeDescriptorStr,
            final String typeSignatureStr, final String[] parameterNames, final int[] parameterModifiers,
            final AnnotationInfo[][] parameterAnnotationInfo) {
        this.definingClassName = definingClassName;
        this.name = methodName;
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
     * Returns the name of the method. Note that constructors are named {@code "<init>"}, and private static class
     * initializer blocks are named {@code "<clinit>"}.
     * 
     * @return The name of the method.
     */
    public String getName() {
        return name;
    }

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
     * Get the name of the class this method is defined within.
     * 
     * @return The name of the enclosing class.
     */
    public String getDefiningClassName() {
        return definingClassName;
    }

    /**
     * Returns the defining class name, so that {@link #getClassInfo()} returns the {@link ClassInfo} object for the
     * defining class.
     */
    @Override
    protected String getClassName() {
        return definingClassName;
    }

    /**
     * Get the class this method is defined within.
     * 
     * @return The class this method is defined within.
     */
    public ClassInfo getDefiningClassInfo() {
        return getClassInfo();
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
                typeDescriptor = MethodTypeSignature.parse(definingClassName, typeDescriptorStr, scanResult);
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
                typeSignature = MethodTypeSignature.parse(definingClassName, typeSignatureStr, scanResult);
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

    /** Get the names of any classes in the type descriptor or type signature. */
    @Override
    protected void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
        final MethodTypeSignature methodSig = getTypeSignature();
        if (methodSig != null) {
            methodSig.getClassNamesFromTypeDescriptors(classNames);
        }
        final MethodTypeSignature methodDesc = getTypeDescriptor();
        if (methodDesc != null) {
            methodDesc.getClassNamesFromTypeDescriptors(classNames);
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo annotationInfo : annotationInfo) {
                annotationInfo.getClassNamesFromTypeDescriptors(classNames);
            }
        }
        for (final MethodParameterInfo parameterInfo : getParameterInfo()) {
            final AnnotationInfo[] paramAnnotationInfo = parameterInfo.getAnnotationInfo();
            if (paramAnnotationInfo != null) {
                for (final AnnotationInfo annotationInfo : paramAnnotationInfo) {
                    annotationInfo.getClassNamesFromTypeDescriptors(classNames);
                }
            }
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
        return "<init>".equals(name);
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

    /**
     * Get the available information on method parameters.
     * 
     * @return The {@link MethodParameterInfo} objects for the method parameters, one per parameter.
     */
    public MethodParameterInfo[] getParameterInfo() {
        if (parameterInfo == null) {
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
                        "typeSignatureParamTypes.size() > typeDescriptorParamTypes.size() for method "
                                + definingClassName + "." + name);
            }

            // Figure out number of other fields that need alignment, and check length for consistency 
            final int otherParamMax = Math.max(parameterNames == null ? 0 : parameterNames.length,
                    Math.max(parameterModifiers == null ? 0 : parameterModifiers.length,
                            parameterAnnotationInfo == null ? 0 : parameterAnnotationInfo.length));
            if (otherParamMax > numParams) {
                // Should not happen
                throw new RuntimeException("Type descriptor for method " + definingClassName + "." + name
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
            parameterInfo = new MethodParameterInfo[numParams];
            for (int i = 0; i < numParams; i++) {
                parameterInfo[i] = new MethodParameterInfo(
                        paramAnnotationInfoAligned == null ? null : paramAnnotationInfoAligned[i],
                        paramModifiersAligned == null ? 0 : paramModifiersAligned[i], paramTypeDescriptors.get(i),
                        paramTypeSignaturesAligned == null ? null : paramTypeSignaturesAligned.get(i),
                        paramNamesAligned == null ? null : paramNamesAligned[i], scanResult);
            }
        }
        return parameterInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a list of annotations on this method, along with any annotation parameter values.
     * 
     * @return a list of annotations on this method, along with any annotation parameter values, wrapped in
     *         {@link AnnotationInfo} objects, or the empty list if none.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableAnnotationInfo() before #scan()");
        }
        return annotationInfo == null ? AnnotationInfoList.EMPTY_LIST : annotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

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
        return definingClassName.equals(other.definingClassName)
                && typeDescriptorStr.equals(other.typeDescriptorStr) && name.equals(other.name);
    }

    /** Use hash code of class name, method name and type descriptor. */
    @Override
    public int hashCode() {
        return name.hashCode() + typeDescriptorStr.hashCode() * 11 + definingClassName.hashCode() * 57;
    }

    /** Sort in order of class name, method name, then type descriptor. */
    @Override
    public int compareTo(final MethodInfo other) {
        final int diff0 = definingClassName.compareTo(other.definingClassName);
        if (diff0 != 0) {
            return diff0;
        }
        final int diff1 = name.compareTo(other.name);
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
        if (name != null) {
            buf.append(name);
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

            final AnnotationInfo[] annInfo = paramInfo.annotationInfo;
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
                            "Got non-array type for last parameter of varargs method " + name);
                }
                final ArrayTypeSignature arrayType = (ArrayTypeSignature) paramType;
                if (arrayType.getNumDimensions() == 0) {
                    throw new IllegalArgumentException(
                            "Got a zero-dimension array type for last parameter of varargs method " + name);
                }
                // Replace last "[]" with "..."
                buf.append(new ArrayTypeSignature(arrayType.getElementTypeSignature(),
                        arrayType.getNumDimensions() - 1).toString());
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
