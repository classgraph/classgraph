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
package io.github.classgraph.issues.issue152;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue152Test.
 */
public class Issue152Test {
    /** The test field. */
    public Map<Integer, Map<String, Boolean>> testField;

    /**
     * Test method.
     *
     * @param param0
     *            the param 0
     * @param param2
     *            the param 2
     * @param param3
     *            the param 3
     * @param param4
     *            the param 4
     * @param param5
     *            the param 5
     * @param param6
     *            the param 6
     * @param param7
     *            the param 7
     * @param param8
     *            the param 8
     * @param param9
     *            the param 9
     * @return the sets the
     */
    public Set<Integer> testMethod(final List<String[]> param0, final Map<String, Map<Integer, Boolean>> param2,
            final double[][][] param3, final int param4, final TestType[] param5,
            final Set<? extends TestType> param6, final List<? super TestType> param7, final Map<Integer, ?> param8,
            final Set<String>[] param9) {
        return null;
    }

    /**
     * The Class TestType.
     */
    public static class TestType {
    }

    /**
     * Issue 152 test.
     */
    @Test
    public void issue152Test() {
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
