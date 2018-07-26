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
package io.github.lukehutch.fastclasspathscanner.test.methodinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;

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
        try {
            // .enableSaveMethodInfo() not called
            new FastClasspathScanner().whitelistPackages(MethodInfoTest.class.getPackage().getName()).scan()
                    .getClassInfo(MethodInfoTest.class.getName()).getMethodInfo();
            throw new RuntimeException("Fail");
        } catch (final Exception e) {
            // Pass
        }
    }

    @Test
    public void getMethodInfo() throws Exception {
        assertThat(new FastClasspathScanner().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().scan()
                .getClassInfo(MethodInfoTest.class.getName()).getMethodInfo().getAsStrings()).containsOnly( //
                        "@" + ExternalAnnotation.class.getName() //
                                + " public final int publicMethodWithArgs"
                                + "(java.lang.String, char, long, float[], byte[][], "
                                + "java.util.List<java.lang.Float>, int[]...)",
                        "@" + Test.class.getName() + " public void methodInfoNotEnabled()",
                        "@" + Test.class.getName() + " public void getMethodInfo()",
                        "@" + Test.class.getName() + " public void getConstructorInfo()",
                        "@" + Test.class.getName() + " public void getMethodInfoIgnoringVisibility()");
    }

    @Test
    public void getConstructorInfo() throws Exception {
        assertThat(new FastClasspathScanner().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableMethodInfo().scan().getClassInfo(MethodInfoTest.class.getName()).getConstructorInfo()
                .getAsStrings()).containsOnly("public <init>()");
    }

    @Test
    public void getMethodInfoIgnoringVisibility() throws Exception {
        assertThat(new FastClasspathScanner().whitelistPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().ignoreMethodVisibility().scan()
                .getClassInfo(MethodInfoTest.class.getName()).getMethodInfo().getAsStrings()).containsOnly( //
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
