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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.util.zip.Inflater;

import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.recycler.Resettable;

/**
 * Wrapper class that allows an {@link Inflater} instance to be reset for reuse and then recycled by a
 * {@link Recycler}.
 */
public class RecyclableInflater implements Resettable, AutoCloseable {
    /** Create a new {@link Inflater} instance with the "nowrap" option (which is needed for zipfile entries). */
    private final Inflater inflater = new Inflater(/* nowrap = */ true);

    /**
     * Get the {@link Inflater} instance.
     *
     * @return the {@link Inflater} instance.
     */
    public Inflater getInflater() {
        return inflater;
    }

    /** Called when an {@link Inflater} instance is recycled, to reset the inflater so it can accept new input. */
    @Override
    public void reset() {
        inflater.reset();
    }

    /** Called when the {@link Recycler} instance is closed, to destroy the {@link Inflater} instance. */
    @Override
    public void close() {
        inflater.end();
    }
}