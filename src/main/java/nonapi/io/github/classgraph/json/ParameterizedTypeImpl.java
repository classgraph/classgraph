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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An implementation of {@link ParameterizedType}, used to replace type variables with concrete types. */
class ParameterizedTypeImpl implements ParameterizedType {

    /** The actual type arguments. */
    private final Type[] actualTypeArguments;

    /** The raw type. */
    private final Class<?> rawType;

    /** The owner type. */
    private final Type ownerType;

    /** The type parameters of {@link Map} instances of unknown generic type. */
    public static final Type MAP_OF_UNKNOWN_TYPE = new ParameterizedTypeImpl(Map.class,
            new Type[] { Object.class, Object.class }, null);

    /** The type parameter of {@link List} instances of unknown generic type. */
    public static final Type LIST_OF_UNKNOWN_TYPE = new ParameterizedTypeImpl(List.class,
            new Type[] { Object.class }, null);

    /**
     * Constructor.
     *
     * @param rawType
     *            the raw type
     * @param actualTypeArguments
     *            the actual type arguments
     * @param ownerType
     *            the owner type
     */
    ParameterizedTypeImpl(final Class<?> rawType, final Type[] actualTypeArguments, final Type ownerType) {
        this.actualTypeArguments = actualTypeArguments;
        this.rawType = rawType;
        this.ownerType = (ownerType != null) ? ownerType : rawType.getDeclaringClass();
        if (rawType.getTypeParameters().length != actualTypeArguments.length) {
            throw new IllegalArgumentException("Argument length mismatch");
        }
    }

    /* (non-Javadoc)
     * @see java.lang.reflect.ParameterizedType#getActualTypeArguments()
     */
    @Override
    public Type[] getActualTypeArguments() {
        return actualTypeArguments.clone();
    }

    /* (non-Javadoc)
     * @see java.lang.reflect.ParameterizedType#getRawType()
     */
    @Override
    public Class<?> getRawType() {
        return rawType;
    }

    /* (non-Javadoc)
     * @see java.lang.reflect.ParameterizedType#getOwnerType()
     */
    @Override
    public Type getOwnerType() {
        return ownerType;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ParameterizedType)) {
            return false;
        }
        final ParameterizedType other = (ParameterizedType) obj;
        return Objects.equals(ownerType, other.getOwnerType()) && Objects.equals(rawType, other.getRawType())
                && Arrays.equals(actualTypeArguments, other.getActualTypeArguments());
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(ownerType) ^ Objects.hashCode(rawType);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        if (ownerType == null) {
            buf.append(rawType.getName());
        } else {
            if (ownerType instanceof Class) {
                buf.append(((Class<?>) ownerType).getName());
            } else {
                buf.append(ownerType.toString());
            }
            buf.append('$');
            if (ownerType instanceof ParameterizedTypeImpl) {
                final String simpleName = rawType.getName()
                        .replace(((ParameterizedTypeImpl) ownerType).rawType.getName() + "$", "");
                buf.append(simpleName);
            } else {
                buf.append(rawType.getSimpleName());
            }
        }
        if (actualTypeArguments != null && actualTypeArguments.length > 0) {
            buf.append('<');
            boolean first = true;
            for (final Type t : actualTypeArguments) {
                if (first) {
                    first = false;
                } else {
                    buf.append(", ");
                }
                buf.append(t.toString());
            }
            buf.append('>');
        }
        return buf.toString();
    }
}