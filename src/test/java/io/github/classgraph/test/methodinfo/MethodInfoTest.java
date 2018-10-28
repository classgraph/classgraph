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
package io.github.classgraph.test.methodinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList.MethodInfoFilter;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalAnnotation;

public class MethodInfoTest {
    @ExternalAnnotation
    public final int publicMethodWithArgs(final String str, final char c, final long j, final float[] f,
            final byte[][] b, final List<Float> l, final int[]... varargs) {
        return 0;
    }

    @SuppressWarnings("unused")
    private static String[] privateMethod() {
        return null;
    };

    @Test
    public void methodInfoNotEnabled() throws Exception {
        // .enableSaveMethodInfo() not called
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .scan()) {
            scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo();
            throw new RuntimeException("Fail");
        } catch (final Exception e) {
            // Pass
        }
    }

    @Test
    public void getMethodInfo() throws Exception {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .filter(new MethodInfoFilter() {
                        @Override
                        public boolean accept(final MethodInfo methodInfo) {
                            // JDK 10 fix
                            return !methodInfo.getName().equals("$closeResource");
                        }
                    }).getAsStrings()).containsExactlyInAnyOrder( //
                            "@" + ExternalAnnotation.class.getName() //
                                    + " public final int publicMethodWithArgs"
                                    + "(java.lang.String, char, long, float[], byte[][], "
                                    + "java.util.List<java.lang.Float>, int[]...)",
                            "@" + Test.class.getName() + " public void methodInfoNotEnabled()",
                            "@" + Test.class.getName() + " public void getMethodInfo()",
                            "@" + Test.class.getName() + " public void getConstructorInfo()",
                            "@" + Test.class.getName() + " public void getMethodInfoIgnoringVisibility()");
        }
    }

    @Test
    public void getConstructorInfo() throws Exception {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableMethodInfo().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getConstructorInfo().getAsStrings())
                    .containsExactlyInAnyOrder("public <init>()");
        }
    }

    @Test
    public void getMethodInfoIgnoringVisibility() throws Exception {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .filter(new MethodInfoFilter() {
                        @Override
                        public boolean accept(final MethodInfo methodInfo) {
                            // JDK 10 fix
                            return !methodInfo.getName().equals("$closeResource");
                        }
                    }).getAsStrings()).containsExactlyInAnyOrder( //
                            "@" + ExternalAnnotation.class.getName() //
                                    + " public final int publicMethodWithArgs"
                                    + "(java.lang.String, char, long, float[], byte[][], "
                                    + "java.util.List<java.lang.Float>, int[]...)",
                            "private static java.lang.String[] privateMethod()",
                            "@" + Test.class.getName() + " public void methodInfoNotEnabled()",
                            "@" + Test.class.getName() + " public void getMethodInfo()",
                            "@" + Test.class.getName() + " public void getConstructorInfo()",
                            "@" + Test.class.getName() + " public void getMethodInfoIgnoringVisibility()");
        }
    }
}
