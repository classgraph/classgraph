/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.HashMap;

/**
 * Used to wrap a map with lazy evaluation, so work is not done unless it is needed. (e.g. if you don't use the
 * annotations features of the API, most of the annotations data structures are not built.)
 */
public abstract class LazyMap<K, V> {
    protected final HashMap<K, V> map = new HashMap<>();
    private boolean initialized = false;

    public void clear() {
        map.clear();
        initialized = false;
    }

    public HashMap<K, V> resolve() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        return map;
    }

    public HashMap<K, V> getRawMap() {
        return map;
    }

    @Override
    public String toString() {
        return map.toString();
    }

    public abstract void initialize();
}
