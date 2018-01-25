/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Richard Begg
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
package io.github.lukehutch.fastclasspathscanner.issues.issue167;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue167.a.TestA;
import io.github.lukehutch.fastclasspathscanner.issues.issue167.a.b.TestAB;

public class Issue167Test {
    public static List<Class<?>> classes = Arrays.asList(new Class<?>[] { TestA.class, TestAB.class });
    public static List<String> packages = classes.stream().map(c -> c.getPackage().getName())
            .collect(Collectors.toList());
    public static List<String> classNames = classes.stream().map(c -> c.getName()).collect(Collectors.toList());

    @Test
    public void scanPackagesTest1() throws IOException {
        assertEquals(classNames, new FastClasspathScanner(packages.toArray(new String[packages.size()]))
                .disableRecursiveScanning().scan().getNamesOfAllClasses());
    }

    @Test
    public void scanPackagesTest2() throws IOException {
        final List<String> reversedPackages = new ArrayList<>(packages);
        Collections.reverse(reversedPackages);
        assertEquals(classNames,
                new FastClasspathScanner(reversedPackages.toArray(new String[reversedPackages.size()]))
                        .disableRecursiveScanning().scan().getNamesOfAllClasses());
    }
}
