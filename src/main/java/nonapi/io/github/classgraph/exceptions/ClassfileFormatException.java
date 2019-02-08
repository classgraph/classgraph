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
package nonapi.io.github.classgraph.exceptions;

/** Thrown when a classfile's contents are not in the correct format. */
public class ClassfileFormatException extends IllegalArgumentException {
    /** serialVersionUID. */
    static final long serialVersionUID = 1L;

    /** Constructor. */
    public ClassfileFormatException() {
        super();
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     */
    public ClassfileFormatException(final String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param cause
     *            the cause
     */
    public ClassfileFormatException(final Throwable cause) {
        super(cause);
    }

    /**
     * Constructor.
     *
     * @param message
     *            the message
     * @param cause
     *            the cause
     */
    public ClassfileFormatException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
