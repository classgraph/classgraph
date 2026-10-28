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
package io.github.classgraph.vfs.internal.slice;

import java.util.Arrays;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.reader.RandomAccessArrayReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;

/** A byte array slice. */
public final class ArraySlice extends Slice {
    /** The wrapped byte array. */
    public final byte[] arr;

    /**
     * Constructor for treating a range of an array as a slice.
     *
     * @param parentSlice
     *            the parent slice
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    private ArraySlice(final ArraySlice parentSlice, final long offset, final long length, final Vfs vfs) {
        super(parentSlice, offset, length, vfs);
        this.arr = parentSlice.arr;
    }

    /**
     * Constructor for treating a whole array as a slice.
     *
     * @param arr
     *            the array containing the slice.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    public ArraySlice(final byte[] arr, final Vfs vfs) {
        super(arr.length, vfs);
        this.arr = arr;
    }

    /**
     * Slice this slice to form a sub-slice.
     *
     * @param offset
     *            the offset relative to the start of this slice to use as the start of the sub-slice.
     * @param length
     *            the length of the sub-slice.
     * @return the slice
     */
    @Override
    public Slice slice(final long offset, final long length) {
        return new ArraySlice(this, offset, length, vfs);
    }

    /**
     * Load the slice as a byte array. Returns {@link #arr} directly when the slice covers the entire array, or a
     * copy of the slice range otherwise.
     *
     * @return the content of the slice
     */
    @Override
    public byte[] load() {
        if (sliceStartPos == 0L && sliceLength == arr.length) {
            // Fast path -- return whole array, if the array is the whole slice
            return arr;
        } else {
            // Copy range of array, if it is a slice
            return Arrays.copyOfRange(arr, (int) sliceStartPos, (int) (sliceStartPos + sliceLength));
        }
    }

    /**
     * Return a new random access reader.
     *
     * @return the random access reader
     */
    @Override
    public RandomAccessReader randomAccessReader() {
        return new RandomAccessArrayReader(arr, (int) sliceStartPos, (int) sliceLength);
    }
}
