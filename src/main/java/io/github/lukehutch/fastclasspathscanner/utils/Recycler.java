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
 * Copyright (c) 2016 Luke Hutchison
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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycle instances of type T. The method T#close() is called when this class' own close() method is called. Use
 * RuntimeException for type E if the newInstance() method does not throw an exception.
 */
public abstract class Recycler<T extends AutoCloseable, E extends Exception> implements AutoCloseable {
    /** Instances that have been allocated. */
    private final ConcurrentLinkedQueue<T> allocatedInstances = new ConcurrentLinkedQueue<>();

    /** Instances that have been allocated but are unused. */
    private final ConcurrentLinkedQueue<T> unusedInstances = new ConcurrentLinkedQueue<>();

    /** Acquire or allocate an instance. */
    public T acquire() throws E {
        final T instance = unusedInstances.poll();
        if (instance != null) {
            return instance;
        }
        final T newInstance = newInstance();
        if (newInstance != null) {
            allocatedInstances.add(newInstance);
            return newInstance;
        } else {
            throw new RuntimeException("Failed to allocate a new recyclable instance");
        }
    }

    /** Release/recycle an instance. */
    public void release(final T instance) {
        if (instance != null) {
            unusedInstances.add(instance);
        }
    }

    /** Call this only after all instances have been released. */
    @Override
    public void close() {
        final int unreleasedInstances = allocatedInstances.size() - unusedInstances.size();
        if (unreleasedInstances != 0) {
            throw new RuntimeException("Unreleased instances: " + unreleasedInstances);
        }
        for (T instance; (instance = allocatedInstances.poll()) != null;) {
            try {
                instance.close();
            } catch (final Exception e) {
                // Ignore
            }
        }
        unusedInstances.clear();
    }

    /** Create a new instance. */
    public abstract T newInstance() throws E;
}
