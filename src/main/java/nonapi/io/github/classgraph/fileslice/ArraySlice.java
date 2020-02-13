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
 * Copyright (c) 2020 Luke Hutchison
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
package nonapi.io.github.classgraph.fileslice;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessArrayReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;

/** A byte array slice. */
public class ArraySlice extends Slice {
    /** The wrapped byte array. */
    public byte[] arr;

    /** Constructor. */
    private ArraySlice(final ArraySlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
        this.arr = parentSlice.arr;
    }

    /** Constructor. */
    public ArraySlice(final byte[] arr, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        super(arr.length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
        this.arr = arr;
    }

    @Override
    public Slice slice(final long offset, final long length, final boolean isDeflatedZipEntry,
            final long inflatedLengthHint) {
        if (this.isDeflatedZipEntry) {
            throw new IllegalArgumentException("Cannot slice a deflated zip entry");
        }
        return new ArraySlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
    }

    @Override
    public byte[] load() throws IOException {
        if (isDeflatedZipEntry) {
            // Deflate into RAM if necessary
            try (InputStream inputStream = open()) {
                return NestedJarHandler.readAllBytesAsArray(inputStream, inflatedLengthHint);
            }
        } else if (sliceStartPos == 0L && sliceLength == arr.length) {
            // Fast path -- return whole array, if the array is the whole slice and is not deflated
            return arr;
        } else {
            // Copy range of array, if it is a slice and it is not deflated
            return Arrays.copyOfRange(arr, (int) sliceStartPos, (int) (sliceStartPos + sliceLength));
        }
    }

    @Override
    public RandomAccessReader randomAccessReader() {
        return new RandomAccessArrayReader(arr, (int) sliceStartPos, (int) sliceLength);
    }
}