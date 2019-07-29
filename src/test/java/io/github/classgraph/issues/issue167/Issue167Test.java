/*
 * This file is part of ClassGraph.
 *
 * Author: Richard Begg
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
package io.github.classgraph.issues.issue167;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.issues.issue167.a.TestA;
import io.github.classgraph.issues.issue167.a.b.TestAB;

/**
 * Issue167Test.
 */
public class Issue167Test {
    /** The classes. */
    public static List<Class<?>> classes = Arrays.asList(TestA.class, TestAB.class);

    /** The packages. */
    public static List<String> packages = new ArrayList<>();

    /** The class names. */
    public static List<String> classNames = new ArrayList<>();
    static {
        for (final Class<?> c : classes) {
            packages.add(c.getPackage().getName());
            classNames.add(c.getName());
        }
    }

    /**
     * Scan packages test 1.
     */
    @Test
    public void scanPackagesTest1() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackagesNonRecursive(packages.toArray(new String[0]))
                .enableClassInfo().scan()) {
            assertEquals(classNames, scanResult.getAllClasses().getNames());
        }
    }

    /**
     * Scan packages test 2.
     */
    @Test
    public void scanPackagesTest2() {
        final List<String> reversedPackages = new ArrayList<>(packages);
        Collections.reverse(reversedPackages);
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackagesNonRecursive(reversedPackages.toArray(new String[0])).enableClassInfo().scan()) {
            assertEquals(classNames, scanResult.getAllClasses().getNames());
        }
    }
}
