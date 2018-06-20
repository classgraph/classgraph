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
package io.github.lukehutch.fastclasspathscanner.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;

/** A list of element type TypeVariableToResolvedType. */
class TypeVariableToResolvedTypeList extends ArrayList<TypeVariableToResolvedType> {
    public TypeVariableToResolvedTypeList(final int length) {
        super(length);
    }

    public TypeVariableToResolvedTypeList() {
        super();
    }

    /** Resolve the type variables in a type using a type variable resolution list, producing a resolved type. */
    static Type resolveTypeVariables(final Type type, final TypeVariableToResolvedTypeList typeResolutions) {
        if (type instanceof Class<?>) {
            // Arrays and non-generic classes have no type variables
            return type;
        } else if (type instanceof ParameterizedType) {
            // Recursively resolve parameterized types
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Type[] typeArgs = parameterizedType.getActualTypeArguments();
            final Type[] typeArgsResolved = new Type[typeArgs.length];
            boolean resolvedOneOrMoreParams = false;
            for (int i = 0; i < typeArgs.length; i++) {
                typeArgsResolved[i] = resolveTypeVariables(typeArgs[i], typeResolutions);
                if (!typeArgsResolved[i].equals(typeArgs[i])) {
                    resolvedOneOrMoreParams = true;
                }
            }
            if (!resolvedOneOrMoreParams) {
                // There were no type parameters to resolve
                return type;
            } else {
                // Return new ParameterizedType that wraps the resolved type args
                return new ParameterizedTypeImpl((Class<?>) parameterizedType.getRawType(), typeArgsResolved,
                        parameterizedType.getOwnerType());
            }
        } else if (type instanceof TypeVariable) {
            // Look up concrete type for type variable
            final TypeVariable<?> typeVariable = (TypeVariable<?>) type;
            Type typeResolved = null;
            if (typeResolutions == null) {
                throw new IllegalArgumentException(
                        "No known type variable definitions; could not resolve type vairable " + typeVariable);
            }
            for (final TypeVariableToResolvedType mapping : typeResolutions) {
                if (mapping.typeVariable.getName().equals(typeVariable.getName())) {
                    typeResolved = mapping.resolvedType;
                    break;
                }
            }
            if (typeResolved == null) {
                throw new IllegalArgumentException("Could not resolve type variable " + typeVariable);
            }
            return typeResolved;
        } else {
            throw new RuntimeException("Got unexpected type: " + type);
        }
    }
}