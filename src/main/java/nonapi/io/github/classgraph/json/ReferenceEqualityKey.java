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

/**
 * An object for wrapping a HashMap key so that the hashmap performs reference equality on the keys, not equals()
 * equality.
 *
 * @param <K>
 *            the key type
 */
public class ReferenceEqualityKey<K> {

    /** The wrapped key. */
    private final K wrappedKey;

    /**
     * Constructor.
     *
     * @param wrappedKey
     *            the wrapped key
     */
    public ReferenceEqualityKey(final K wrappedKey) {
        this.wrappedKey = wrappedKey;
    }

    /**
     * Get the wrapped key.
     *
     * @return the wrapped key.
     */
    public K get() {
        return wrappedKey;
    }

    /**
     * Hash code.
     *
     * @return the int
     */
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final K key = wrappedKey;
        // Don't call key.hashCode(), because that can be an expensive (deep) hashing method,
        // e.g. for ByteBuffer, it is based on the entire contents of the buffer
        return key == null ? 0 : System.identityHashCode(key);
    }

    /**
     * Equals.
     *
     * @param obj
     *            the obj
     * @return true, if successful
     */
    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ReferenceEqualityKey)) {
            return false;
        }
        return wrappedKey == ((ReferenceEqualityKey<?>) obj).wrappedKey;
    }

    /**
     * To string.
     *
     * @return the string
     */
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final K key = wrappedKey;
        return key == null ? "null" : key.toString();
    }
}