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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class Utils {

    /** Returns true if the path ends with a JAR extension */
    public static boolean isJar(final String path) {
        final String pathLower = path.toLowerCase();
        return pathLower.endsWith(".jar") || pathLower.endsWith(".zip") || pathLower.endsWith(".war")
                || pathLower.endsWith(".car");
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Convert a collection into a sorted list. */
    @SafeVarargs
    public static <T extends Comparable<T>> ArrayList<T> sortedCopy(Collection<T>... collections) {
        final ArrayList<T> copy = new ArrayList<>();
        for (Collection<T> collection : collections) {
            copy.addAll(collection);
        }
        Collections.sort(copy);
        return copy;
    }
}
