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
 * Readers that read binary content a value at a time, in a byte order fixed by the content.
 *
 * <p>
 * {@link io.github.classgraph.vfs.reader.RandomAccessReader} reads a value at any offset, and
 * {@link io.github.classgraph.vfs.reader.SequentialReader} reads values one after another from a position that
 * advances. There are readers over a byte array, a {@link java.nio.ByteBuffer} and a
 * {@link java.nio.channels.FileChannel}, and {@link io.github.classgraph.vfs.reader.RandomAccessOrSequentialReader}
 * reads a stream, such as a {@link io.github.classgraph.vfs.VfsEntry}, buffering it as it goes so that it can be
 * read either way. {@link io.github.classgraph.vfs.reader.RandomAccessReader} describes the rules they all follow:
 * byte order, the end of the content, and threads.
 */
@NullMarked
package io.github.classgraph.vfs.reader;

import org.jspecify.annotations.NullMarked;
