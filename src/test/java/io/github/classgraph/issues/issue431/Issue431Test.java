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
package io.github.classgraph.issues.issue431;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue431Test.
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

    /**
     * Test field equality.
     * 
     * @param fieldName  The field name
     * @param classInfo1 The first ClassInfo
     * @param classInfo2 The second ClassInfo
     */
    private void testFieldEquality(final String fieldName, final ClassInfo classInfo1, final ClassInfo classInfo2) {
        assertThat(Objects.equals(classInfo1.getFieldInfo(fieldName).getConstantInitializerValue(),
                classInfo2.getFieldInfo(fieldName).getConstantInitializerValue())).isTrue();
    }

    /** Test serializing and deserializing primitive types. */
    @Test
    public void primitiveTypeSerialization() {
        final var classGraph = new ClassGraph().acceptPackages(Issue431Test.class.getPackage().getName())
                .enableAllInfo();
        try (var scanResult1 = classGraph.scan()) {
            final var classInfo1 = scanResult1.getClassInfo(X.class.getName());
            assertThat(classInfo1).isNotNull();
            final var jsonResult = scanResult1.toJSON(2);
            final var scanResult2 = ScanResult.fromJSON(jsonResult);
            final var classInfo2 = scanResult2.getClassInfo(X.class.getName());
            assertThat(classInfo2).isNotNull();
            for (var fieldName = 'a'; fieldName <= 'i'; fieldName++) {
                testFieldEquality(String.valueOf(fieldName), classInfo1, classInfo2);
            }
        }
    }
}
