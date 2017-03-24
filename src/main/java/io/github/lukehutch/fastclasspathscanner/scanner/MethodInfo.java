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
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Holds metadata about methods of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class MethodInfo {
    private final String methodName;
    private final int modifiers;
    private final List<String> annotationNames;
    private final List<String> parameterTypeStrs;
    private final String returnTypeStr;
    private final boolean isConstructor;

    public MethodInfo(final String methodName, final int modifiers, final String typeDescriptor,
            final List<String> annotationNames, final boolean isConstructor) {
        this.methodName = methodName;
        this.modifiers = modifiers;

        final List<String> typeNames = ReflectionUtils.parseTypeDescriptor(typeDescriptor);
        if (typeNames.size() < 1) {
            throw new IllegalArgumentException("Invalid type descriptor for method: " + typeDescriptor);
        }
        this.parameterTypeStrs = typeNames.subList(0, typeNames.size() - 1);
        this.returnTypeStr = typeNames.get(typeNames.size() - 1);

        this.annotationNames = annotationNames.isEmpty() ? Collections.<String>emptyList() : annotationNames;
        this.isConstructor = isConstructor;
    }

    /** Get the method modifiers as a string, e.g. "public static final". */
    public String getModifiers() {
        return ReflectionUtils.modifiersToString(modifiers, /* isMethod = */ true);
    }

    /** Returns true if this method is a constructor. */
    public boolean isConstructor() {
        return isConstructor;
    }

    /** Returns the name of the method. */
    public String getMethodName() {
        return methodName;
    }

    /** Returns the access flags of the method. */
    public int getAccessFlags() {
        return modifiers;
    }

    /**
     * Returns the return type for the method in string representation, e.g. "char[]". If this is a constructor, the
     * returned type will be "void".
     */
    public String getReturnTypeStr() {
        return returnTypeStr;
    }

    /** Returns the parameter types for the method in string representation, e.g. ["int", "List", "com.abc.XYZ"]. */
    public List<String> getParameterTypeStrs() {
        return parameterTypeStrs;
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
