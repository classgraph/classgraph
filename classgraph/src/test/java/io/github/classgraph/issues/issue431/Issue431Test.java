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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.issues.issue431;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * The constant initializer value of a static final field of any primitive type is read as the corresponding boxed
 * type.
 */
public class Issue431Test {
    /**
     * Class X.
     */
    public static class X {
        /** a */
        static final int a = Integer.MAX_VALUE;
        /** b */
        static final long b = 2L;
        /** c */
        static final short c = (short) 3;
        /** d */
        static final char d = 'd';
        /** e */
        static final boolean e = true;
        /** f */
        static final byte f = (byte) 10;
        /** g */
        static final float g = 1.0F;
        /** h */
        static final float h = 0.0F;
        /** i */
        static final double i = 1.0D;
    }

    /** Read the constant initializer value of a field of each primitive type. */
    @Test
    public void primitiveConstantInitializerValues() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue431Test.class.getPackage().getName()).enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(X.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getFieldInfo("a").getConstantInitializerValue()).isEqualTo(Integer.MAX_VALUE);
            assertThat(classInfo.getFieldInfo("b").getConstantInitializerValue()).isEqualTo(2L);
            assertThat(classInfo.getFieldInfo("c").getConstantInitializerValue()).isEqualTo((short) 3);
            assertThat(classInfo.getFieldInfo("d").getConstantInitializerValue()).isEqualTo('d');
            assertThat(classInfo.getFieldInfo("e").getConstantInitializerValue()).isEqualTo(true);
            assertThat(classInfo.getFieldInfo("f").getConstantInitializerValue()).isEqualTo((byte) 10);
            assertThat(classInfo.getFieldInfo("g").getConstantInitializerValue()).isEqualTo(1.0F);
            assertThat(classInfo.getFieldInfo("h").getConstantInitializerValue()).isEqualTo(0.0F);
            assertThat(classInfo.getFieldInfo("i").getConstantInitializerValue()).isEqualTo(1.0D);
        }
    }
}
