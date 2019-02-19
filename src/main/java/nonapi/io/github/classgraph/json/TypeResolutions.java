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
package nonapi.io.github.classgraph.json;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

import io.github.classgraph.ClassGraphException;

/** A mapping from {@link TypeVariable} to resolved {@link Type}. */
class TypeResolutions {

    /** The type variables. */
    private final TypeVariable<?>[] typeVariables;

    /** The resolved type arguments. */
    Type[] resolvedTypeArguments;

    /**
     * Produce a list of type variable resolutions from a resolved type, by comparing its actual type parameters
     * with the generic (declared) parameters of its generic type.
     *
     * @param resolvedType
     *            the resolved type
     */
    TypeResolutions(final ParameterizedType resolvedType) {
        typeVariables = ((Class<?>) resolvedType.getRawType()).getTypeParameters();
        resolvedTypeArguments = resolvedType.getActualTypeArguments();
        if (resolvedTypeArguments.length != typeVariables.length) {
            throw new IllegalArgumentException("Type parameter count mismatch");
        }
    }

    /**
     * Resolve the type variables in a type using a type variable resolution list, producing a resolved type.
     *
     * @param type
     *            the type
     * @return the resolved type
     */
    Type resolveTypeVariables(final Type type) {
        if (type instanceof Class<?>) {
            // Arrays and non-generic classes have no type variables
            return type;

        } else if (type instanceof ParameterizedType) {
            // Recursively resolve parameterized types
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Type[] typeArgs = parameterizedType.getActualTypeArguments();
            Type[] typeArgsResolved = null;
            for (int i = 0; i < typeArgs.length; i++) {
                // Recursively revolve each parameter of the type
                final Type typeArgResolved = resolveTypeVariables(typeArgs[i]);
                // Only compare typeArgs to typeArgResolved until the first difference is found
                if (typeArgsResolved == null) {
                    if (!typeArgResolved.equals(typeArgs[i])) {
                        // After the first difference is found, lazily allocate typeArgsResolved 
                        typeArgsResolved = new Type[typeArgs.length];
                        // Go back and copy all the previous args
                        System.arraycopy(typeArgs, 0, typeArgsResolved, 0, i);
                        // Insert the first different arg
                        typeArgsResolved[i] = typeArgResolved;
                    }
                } else {
                    // After the first difference is found, keep copying the resolved args into the array 
                    typeArgsResolved[i] = typeArgResolved;
                }
            }
            if (typeArgsResolved == null) {
                // There were no type parameters to resolve
                return type;
            } else {
                // Return new ParameterizedType that wraps the resolved type args
                return new ParameterizedTypeImpl((Class<?>) parameterizedType.getRawType(), typeArgsResolved,
                        parameterizedType.getOwnerType());
            }

        } else if (type instanceof TypeVariable<?>) {
            // Look up concrete type for type variable
            final TypeVariable<?> typeVariable = (TypeVariable<?>) type;
            for (int i = 0; i < typeVariables.length; i++) {
                if (typeVariables[i].getName().equals(typeVariable.getName())) {
                    return resolvedTypeArguments[i];
                }
            }
            // Could not resolve type variable
            return type;

        } else if (type instanceof GenericArrayType) {
            // Count the array dimensions, and resolve the innermost type of the array
            int numArrayDims = 0;
            Type t = type;
            while (t instanceof GenericArrayType) {
                numArrayDims++;
                t = ((GenericArrayType) t).getGenericComponentType();
            }
            final Type innermostType = t;
            final Type innermostTypeResolved = resolveTypeVariables(innermostType);
            if (!(innermostTypeResolved instanceof Class<?>)) {
                throw new IllegalArgumentException("Could not resolve generic array type " + type);
            }
            final Class<?> innermostTypeResolvedClass = (Class<?>) innermostTypeResolved;

            // Build an array to hold the size of each dimension, filled with zeroes
            final int[] dims = (int[]) Array.newInstance(int.class, numArrayDims);

            // Build a zero-sized array of the required number of dimensions, using the resolved innermost class 
            final Object arrayInstance = Array.newInstance(innermostTypeResolvedClass, dims);

            // Get the class of this array instance -- this is the resolved array type
            return arrayInstance.getClass();

        } else if (type instanceof WildcardType) {
            // TODO: Support WildcardType
            throw ClassGraphException.newClassGraphException("WildcardType not yet supported: " + type);

        } else {
            throw ClassGraphException.newClassGraphException("Got unexpected type: " + type);
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        if (typeVariables.length == 0) {
            return "{ }";
        } else {
            final StringBuilder buf = new StringBuilder();
            buf.append("{ ");
            for (int i = 0; i < typeVariables.length; i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(typeVariables[i]).append(" => ").append(resolvedTypeArguments[i]);
            }
            buf.append(" }");
            return buf.toString();
        }
    }
}