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
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo implements Comparable<MethodInfo> {
    private final String className;
    private final String methodName;
    private final int modifiers;
    private final String typeDescriptor;
    private List<String> typeStrs;
    private final List<String> annotationNames;

    /**
     * The ScanResult (set after the scan is complete, so that we know which ClassLoader to call for any given named
     * class; used for classloading for getType()).
     */
    ScanResult scanResult;

    public MethodInfo(final String className, final String methodName, final int modifiers,
            final String typeDescriptor, final List<String> annotationNames) {
        this.className = className;
        this.methodName = methodName;
        this.modifiers = modifiers;
        this.typeDescriptor = typeDescriptor;
        this.annotationNames = annotationNames.isEmpty() ? Collections.<String> emptyList() : annotationNames;
    }

    /** Get the method modifiers as a string, e.g. "public static final". */
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
    public int getAccessFlags() {
        return modifiers;
    }

    private List<String> getTypeStrs() {
        if (typeStrs != null) {
            return typeStrs;
        } else {
            final List<String> typeStrsList = ReflectionUtils.parseTypeDescriptor(typeDescriptor);
            if (typeStrsList.size() < 1) {
                throw new IllegalArgumentException("Invalid type descriptor for method: " + typeDescriptor);
            }
            return typeStrsList;
        }
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

    /** Returns the parameter types for the method in string representation, e.g. ["int", "List", "com.abc.XYZ"]. */
    public List<String> getParameterTypeStrs() {
        final List<String> typeStrsList = getTypeStrs();
        return typeStrsList.subList(0, typeStrsList.size() - 1);
    }

    /**
     * Returns the parameter types for the method. Note that this calls Class.forName() on the parameter types,
     * which will cause the class to be loaded, and possibly initialized. If the class is initialized, this can
     * trigger side effects.
     * 
     * @throws IllegalArgumentException
     *             if the parameter types of the method could not be loaded.
     */
    public List<Class<?>> getParameterTypes() throws IllegalArgumentException {
        final List<Class<?>> parameterClassRefs = new ArrayList<>();
        for (final String parameterTypeStr : getParameterTypeStrs()) {
            parameterClassRefs.add(ReflectionUtils.typeStrToClass(parameterTypeStr, scanResult));
        }
        return parameterClassRefs;
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

    /** Returns the names of annotations on the method, or the empty list if none. */
    public List<String> getAnnotationNames() {
        return annotationNames;
    }

    /**
     * Returns Class references for the annotations on this method. Note that this calls Class.forName() on the
     * annotation types, which will cause each annotation class to be loaded.
     * 
     * @throws IllegalArgumentException
     *             if the annotation type could not be loaded.
     */
    public List<Class<?>> getAnnotationTypes() throws IllegalArgumentException {
        if (annotationNames.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> annotationClassRefs = new ArrayList<>();
            for (final String annotationName : annotationNames) {
                annotationClassRefs.add(ReflectionUtils.typeStrToClass(annotationName, scanResult));
            }
            return annotationClassRefs;
        }
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

        if (!annotationNames.isEmpty()) {
            for (final String annotationName : annotationNames) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append("@" + annotationName);
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
        final List<String> paramTypes = getParameterTypeStrs();
        final boolean isVarargs = isVarArgs();
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) {
                buf.append(", ");
            }
            final String paramType = paramTypes.get(i);
            if (isVarargs && (i == paramTypes.size() - 1)) {
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
        }
        buf.append(')');

        return buf.toString();
    }
}
