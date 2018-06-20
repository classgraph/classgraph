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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.lukehutch.fastclasspathscanner.json.TinyJSONMapper.Id;

/**
 * The list of fields that can be (de)serialized (non-final, non-transient, non-synthetic, accessible), and their
 * corresponding resolved (concrete) types.
 */
class TypeResolvedFieldsForClass {
    /**
     * The list of fields that can be (de)serialized (non-final, non-transient, non-synthetic, accessible), and
     * their corresponding resolved (concrete) types.
     * 
     * <p>
     * For arrays, the {@link Type} will be a {@code Class<?>} reference where {@link Class#isArray()} is true, and
     * {@link Class#getComponentType()} is the element type (the element type will itself be an array-typed
     * {@code Class<?>} reference for multi-dimensional arrays).
     * 
     * <p>
     * For generics, the {@link Type} will be an implementation of {@link ParameterizedType}.
     */
    final List<FieldResolvedTypeInfo> fieldOrder = new ArrayList<>();

    /** Map from field name to field and resolved type. */
    final Map<String, FieldResolvedTypeInfo> fieldNameToResolvedTypeInfo = new HashMap<>();

    /** If non-null, this is the field that has an {@link Id} annotation. */
    Field idField;
}