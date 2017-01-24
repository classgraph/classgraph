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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Join;

/**
 * Thrown if one or more exceptions were thrown when attempting to call the classloader on a class, or if one or
 * more exceptions were thrown by a MatchProcessor. Call getExceptions() to get the exceptions thrown.
 */
public class MatchProcessorException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final List<Throwable> exceptions;

    MatchProcessorException(final List<Throwable> exceptions, final Exception e) {
        super(e);
        this.exceptions = exceptions;
    }

    MatchProcessorException(final List<Throwable> exceptions, final String msg) {
        super(msg);
        this.exceptions = exceptions;
    }

    /** Get all the exceptions thrown by a MatchProcessor during the scan. */
    public List<Throwable> getExceptions() {
        return exceptions == null ? Collections.<Throwable> emptyList() : exceptions;
    }

    /** Create a MatchProcessorException */
    public static MatchProcessorException newInstance(final List<Throwable> exceptions) {
        if (exceptions.size() == 1) {
            final Throwable throwable = exceptions.get(0);
            // If only one exception was thrown, wrap that exception
            if (throwable instanceof Exception) {
                return new MatchProcessorException(exceptions, (Exception) throwable);
            } else {
                return new MatchProcessorException(exceptions, throwable.toString());
            }
        } else {
            // If multiple exceptions were thrown, list the unique exception messages
            final Set<String> exceptionMsgs = new HashSet<>();
            for (final Throwable e : exceptions) {
                exceptionMsgs.add(e.toString());
            }
            final List<String> exceptionMsgsSorted = new ArrayList<>(exceptionMsgs);
            Collections.sort(exceptionMsgsSorted);
            return new MatchProcessorException(exceptions,
                    "Multiple exceptions thrown of type: " + Join.join(", ", exceptionMsgsSorted)
                            + ". To see individual exceptions, call MatchProcessorException#getExceptions(), "
                            + "or call FastClasspathScanner#verbose() before FastClasspathScanner#scan().");
        }
    }

    /**
     * Create a MatchProcessorException.
     */
    public static MatchProcessorException newInstance(final Throwable throwable) {
        if (throwable instanceof Exception) {
            return new MatchProcessorException(Arrays.asList(throwable), (Exception) throwable);
        } else {
            return new MatchProcessorException(Arrays.asList(throwable), throwable.toString());
        }
    }
}
