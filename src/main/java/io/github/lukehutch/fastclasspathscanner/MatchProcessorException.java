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
package io.github.lukehutch.fastclasspathscanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Join;

/**
 * Thrown if one or more exceptions was thrown during classloading, or by a MatchProcessor once classes are loaded.
 * Call getExceptions() to get all the exceptions thrown during the scan.
 */
public class MatchProcessorException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final List<Exception> exceptions;

    MatchProcessorException(final List<Exception> exceptions, final Exception e) {
        super(e);
        this.exceptions = exceptions;
    }

    MatchProcessorException(final List<Exception> exceptions, final String msg) {
        super(msg);
        this.exceptions = exceptions;
    }

    /** Get all the exceptions thrown by a MatchProcessor during the scan. */
    public List<Exception> getExceptions() {
        return exceptions == null ? Collections.<Exception>emptyList() : exceptions;
    }

    /** Throw a MatchProcessorException */
    public static MatchProcessorException newInstance(final List<Exception> exceptions) {
        if (exceptions.size() == 1) {
            return new MatchProcessorException(exceptions, exceptions.get(0));
        } else {
            final Set<String> exceptionNames = new HashSet<>();
            for (final Exception e : exceptions) {
                exceptionNames.add(e.getMessage());
            }
            final List<String> exceptionNamesSorted = new ArrayList<>(exceptionNames);
            Collections.sort(exceptionNamesSorted);
            return new MatchProcessorException(exceptions,
                    "Multiple exceptions thrown: " + Join.join(", ", exceptionNamesSorted));
        }
    }
}
