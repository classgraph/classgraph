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
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo extends InfoObject implements Comparable<MethodInfo> {
    private final String className;
    private final String methodName;
    private final int modifiers;
    private final String typeDescriptor;
    private List<String> typeStrs;
    private final String[] parameterNames;
    private final int[] parameterAccessFlags;
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
            final String typeDescriptor, final String[] parameterNames, final int[] parameterAccessFlags,
            final List<AnnotationInfo> methodAnnotationInfo, final AnnotationInfo[][] parameterAnnotationInfo) {
        this.className = className;
        this.methodName = methodName;
        this.modifiers = modifiers;
        this.typeDescriptor = typeDescriptor;
        this.parameterNames = parameterNames;
        this.parameterAccessFlags = parameterAccessFlags;
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
        return ReflectionUtils.modifiersToString(modifiers, /* isMethod = */ true);
    }

    /**
     * Returns true if this method is a constructor. Constructors have the method name {@code "<init>"}. This
     * returns false for private static class initializer blocks, which are named {@code "<clinit>"}.
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

    private List<String> getTypeStrs() {
        if (typeStrs == null) {
            typeStrs = ReflectionUtils.parseComplexTypeDescriptor(typeDescriptor);
            if (typeStrs.size() < 1) {
                throw new IllegalArgumentException("Invalid type descriptor for method: " + typeDescriptor);
            }
            return typeStrs;
        }
        return typeStrs;
    }

    /** Returns the internal type descriptor for the method, e.g. "Ljava/lang/String;V" */
    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    /**
     * Returns the return type for the method in string representation, e.g. "char[]". If this is a constructor, the
     * returned type will be "void".
     */
    public String getReturnTypeStr() {
        final List<String> typeStrsList = getTypeStrs();
        return typeStrsList.get(typeStrsList.size() - 1);
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
        return ReflectionUtils.typeStrToClass(getReturnTypeStr(), scanResult);
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /**
     * Returns the parameter types for the method in string representation, e.g. ["int", "List", "com.abc.XYZ"]. If
     * the method has no parameters, returns a zero-sized array.
     */
    public String[] getParameterTypeStrs() {
        final List<String> typeStrsList = getTypeStrs();
        if (typeStrsList.size() == 1) {
            return EMPTY_STRING_ARRAY;
        } else {
            final List<String> paramsOnly = typeStrsList.subList(0, typeStrsList.size() - 1);
            return paramsOnly.toArray(new String[paramsOnly.size()]);
        }
    }

    private static Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

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
        final String[] parameterTypeStrs = getParameterTypeStrs();
        if (parameterTypeStrs.length == 0) {
            return EMPTY_CLASS_ARRAY;
        } else {
            final Class<?>[] parameterClassRefs = new Class<?>[parameterTypeStrs.length];
            for (int i = 0; i < parameterTypeStrs.length; i++) {
                parameterClassRefs[i] = ReflectionUtils.typeStrToClass(parameterTypeStrs[i], scanResult);
            }
            return parameterClassRefs;
        }
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
        // From:
        // http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
        return (modifiers & 0x0040) != 0;
    }

    /** Returns true if this method is a varargs method. */
    public boolean isVarArgs() {
        // From:
        // http://anonsvn.jboss.org/repos/javassist/trunk/src/main/javassist/bytecode/AccessFlag.java
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
     * Note that parameters may be unnamed, in which case the corresponding parameter name will be null.
     */
    public String[] getParameterNames() {
        return parameterNames;
    }

    /**
     * Returns the parameter access flags, if available (only available in classfiles compiled in JDK8 or above
     * using the -parameters commandline switch), otherwise returns null.
     * 
     * Flag bits:
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
    // TODO: Rename to getParameterModifiers()
    public int[] getParameterAccessFlags() {
        return parameterAccessFlags;
    }

    /**
     * Returns the parameter modifiers as a string (e.g. ["final", ""], if available (only available in classfiles
     * compiled in JDK8 or above using the -parameters commandline switch), otherwise returns null.
     */
    // TODO: Rename to getParameterModifierStrs()
    public String[] getParameterModifiers() {
        if (parameterAccessFlags == null) {
            return null;
        }
        final String[] parameterModifierStrs = new String[parameterAccessFlags.length];
        for (int i = 0; i < parameterAccessFlags.length; i++) {
            parameterModifierStrs[i] = ReflectionUtils.modifiersToString(parameterAccessFlags[i],
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
                parameterAnnotationTypes[i][j] = ReflectionUtils.typeStrToClass(parameterAnnotationNames[i][j],
                        scanResult);
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
                annotationClassRefs.add(ReflectionUtils.typeStrToClass(annotationName, scanResult));
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

    /**
     * Test class name, method name and type descriptor for equals().
     */
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
        return className.equals(other.className) && typeDescriptor.equals(other.typeDescriptor)
                && methodName.equals(other.methodName);
    }

    /** Use hash code of class name, method name and type descriptor. */
    @Override
    public int hashCode() {
        return methodName.hashCode() + typeDescriptor.hashCode() * 11 + className.hashCode() * 57;
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
        return typeDescriptor.compareTo(other.typeDescriptor);
    }

    /**
     * Get a string representation of the method. Note that constructors are named {@code "<init>"}, and private
     * static class initializer blocks are named {@code "<clinit>"}.
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(annotation.toString());
            }
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(getModifiers());

        final boolean isConstructor = isConstructor();
        if (!isConstructor) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(getReturnTypeStr());
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(methodName);

        buf.append('(');
        final String[] paramTypes = getParameterTypeStrs();
        if ((parameterNames != null && paramTypes.length != parameterNames.length)
                || (parameterAccessFlags != null && paramTypes.length != parameterAccessFlags.length)
                || (parameterAnnotationInfo != null && paramTypes.length != parameterAnnotationInfo.length)) {
            // Should not happen
            throw new RuntimeException("parameter number mismatch");
        }
        final boolean isVarargs = isVarArgs();
        for (int i = 0; i < paramTypes.length; i++) {
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
            final String paramType = paramTypes[i];
            if (isVarargs && (i == paramTypes.length - 1)) {
                // Show varargs params correctly
                if (!paramType.endsWith("[]")) {
                    throw new IllegalArgumentException(
                            "Got non-array type for last parameter of varargs method " + methodName);
                }
                buf.append(paramType.substring(0, paramType.length() - 2));
                buf.append("...");
            } else {
                buf.append(paramType);
            }
            if (parameterNames != null) {
                final String paramName = parameterNames[i];
                buf.append(' ');
                buf.append(paramName == null ? "_unnamed_param_" + i : paramName);
            }
        }
        buf.append(')');

        return buf.toString();
    }
}
