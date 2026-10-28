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
 * Copyright (c) 2026 Luke Hutchison
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

/**
 * Binary readers for random-access ({@link io.github.classgraph.vfs.reader.RandomAccessReader}) and sequential
 * ({@link io.github.classgraph.vfs.reader.SequentialReader}) reading over byte arrays,
 * {@link java.nio.ByteBuffer}s, {@link java.nio.channels.FileChannel}s, and streams
 * ({@link io.github.classgraph.vfs.reader.RandomAccessOrSequentialReader}).
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: parameters and return values are non-null unless
 * annotated with {@link org.jspecify.annotations.Nullable}, and passing {@code null} for a non-null parameter
 * throws {@link java.lang.NullPointerException}.
 */
@NullMarked
package io.github.classgraph.vfs.reader;

import org.jspecify.annotations.NullMarked;
