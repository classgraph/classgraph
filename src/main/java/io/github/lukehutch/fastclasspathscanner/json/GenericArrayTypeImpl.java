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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.Objects;

/** An implementation of {@link GenericArrayType}, used to replace type variables with concrete types. */
class GenericArrayTypeImpl implements GenericArrayType {
    private final Type genericComponentType;

    GenericArrayTypeImpl(final Type componentType) {
        genericComponentType = componentType;
    }

    @Override
    public Type getGenericComponentType() {
        return genericComponentType;
    }

    @Override
    public String toString() {
        final Type componentType = getGenericComponentType();
        final StringBuilder buf = new StringBuilder();

        if (componentType instanceof Class) {
            buf.append(((Class<?>) componentType).getName());
        } else {
            buf.append(componentType.toString());
        }
        buf.append("[]");
        return buf.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (o instanceof GenericArrayType) {
            final GenericArrayType other = (GenericArrayType) o;
            return Objects.equals(genericComponentType, other.getGenericComponentType());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(genericComponentType);
    }
}