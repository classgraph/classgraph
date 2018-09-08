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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.issues.issue152;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

public class Issue152Test {
    public Map<Integer, Map<String, Boolean>> testField;

    public Set<Integer> testMethod(final List<String[]> param0, final Map<String, Map<Integer, Boolean>> param2,
            final double[][][] param3, final int param4, final TestType[] param5,
            final Set<? extends TestType> param6, final List<? super TestType> param7, final Map<Integer, ?> param8,
            final Set<String>[] param9) {
        return null;
    }

    public static class TestType {
    }

    @Test
    public void issue152Test() throws IOException {
        final String pkg = Issue152Test.class.getPackage().getName();
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(pkg) //
                .enableMethodInfo() //
                .enableFieldInfo() //
                .scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Issue152Test.class.getName());
            assertThat(classInfo //
                    .getMethodInfo("testMethod") //
                    .get(0).toString()) //
                            .isEqualTo("public java.util.Set<java.lang.Integer> testMethod("
                                    + "java.util.List<java.lang.String[]>, java.util.Map<java.lang.String, "
                                    + "java.util.Map<java.lang.Integer, java.lang.Boolean>>, double[][][], int, "
                                    + TestType.class.getName() + "[], java.util.Set<? extends "
                                    + TestType.class.getName() + ">, java.util.List<? super "
                                    + TestType.class.getName()
                                    + ">, java.util.Map<java.lang.Integer, ?>, java.util.Set<java.lang.String>[])");
            assertThat(classInfo //
                    .getFieldInfo("testField").toString()) //
                            .isEqualTo("public java.util.Map<java.lang.Integer, java.util.Map<java.lang.String, "
                                    + "java.lang.Boolean>> testField");
        }
    }
}
