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
package io.github.lukehutch.fastclasspathscanner.test.methodinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.MethodInfo;
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
            new FastClasspathScanner(MethodInfoTest.class.getPackage().getName()).scan().getClassNameToClassInfo()
                    .get(MethodInfoTest.class.getName()).getMethodInfo();
            throw new RuntimeException("Fail");
        } catch (final Exception e) {
            // Pass
        }
    }

    @Test
    public void getMethodInfo() throws Exception {
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(
                MethodInfoTest.class.getPackage().getName()).enableMethodInfo().scan().getClassNameToClassInfo();

        final List<String> methodInfoStrs = new ArrayList<>();
        final List<MethodInfo> methodInfo = classNameToClassInfo.get(MethodInfoTest.class.getName())
                .getMethodInfo();
        assertThat(methodInfo).isNotNull();
        for (final MethodInfo mi : methodInfo) {
            methodInfoStrs.add(mi.toString());
        }
        assertThat(methodInfoStrs).containsOnly( //
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
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(
                MethodInfoTest.class.getPackage().getName()).enableMethodInfo().scan().getClassNameToClassInfo();

        final List<String> constructorInfoStrs = new ArrayList<>();
        final List<MethodInfo> constructorInfo = classNameToClassInfo.get(MethodInfoTest.class.getName())
                .getConstructorInfo();
        assertThat(constructorInfo).isNotNull();
        for (final MethodInfo ci : constructorInfo) {
            constructorInfoStrs.add(ci.toString());
        }
        assertThat(constructorInfoStrs).containsOnly("public <init>()");
    }

    @Test
    public void getMethodInfoIgnoringVisibility() throws Exception {
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(
                MethodInfoTest.class.getPackage().getName()).enableMethodInfo().ignoreMethodVisibility().scan()
                        .getClassNameToClassInfo();
        final List<String> methodInfoStrs = new ArrayList<>();
        for (final MethodInfo methodInfo : classNameToClassInfo.get(MethodInfoTest.class.getName())
                .getMethodInfo()) {
            methodInfoStrs.add(methodInfo.toString());
        }
        assertThat(methodInfoStrs).containsOnly( //
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
