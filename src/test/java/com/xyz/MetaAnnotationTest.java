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
package com.xyz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class MetaAnnotationTest {
    static ScanResult scanResult;

    @BeforeClass
    public static void setup() {
        scanResult = new ClassGraph().whitelistPackages("com.xyz.meta").enableClassInfo().enableAnnotationInfo()
                .scan();
    }

    @AfterClass
    public static void teardown() {
        scanResult.close();
        scanResult = null;
    }

    @Test
    public void oneLevel() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.E").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.B");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.F").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.B", "com.xyz.meta.A");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.G").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.C");
    }

    @Test
    public void twoLevels() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.J").getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.F", "com.xyz.meta.E", "com.xyz.meta.B", "com.xyz.meta.A");
    }

    @Test
    public void threeLevels() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.L").getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.I", "com.xyz.meta.E", "com.xyz.meta.B", "com.xyz.meta.H");
    }

    @Test
    public void acrossCycle() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.H").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.I");
        assertThat(scanResult.getAnnotationsOnClass("com.xyz.meta.H").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.I", "com.xyz.meta.K", "java.lang.annotation.Retention",
                        "java.lang.annotation.Target");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.E", "com.xyz.meta.H");
        assertThat(scanResult.getAnnotationsOnClass("com.xyz.meta.I").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.L", "com.xyz.meta.H", "java.lang.annotation.Retention",
                        "java.lang.annotation.Target");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.K").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.H");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.D").directOnly().getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.K");
    }

    @Test
    public void cycleAnnotatesSelf() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I").getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.E", "com.xyz.meta.B", "com.xyz.meta.H", "com.xyz.meta.I");
    }

    @Test
    public void namesOfMetaAnnotations() {
        assertThat(scanResult.getAnnotationsOnClass("com.xyz.meta.A").getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.J", "com.xyz.meta.F");
        assertThat(scanResult.getAnnotationsOnClass("com.xyz.meta.C").getNames())
                .containsExactlyInAnyOrder("com.xyz.meta.G");
    }

    @Test
    public void union() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.J")
                .union(scanResult.getClassesWithAnnotation("com.xyz.meta.G")).directOnly().getNames())
                        .containsExactlyInAnyOrder("com.xyz.meta.E", "com.xyz.meta.F", "com.xyz.meta.C");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I")
                .union(scanResult.getClassesWithAnnotation("com.xyz.meta.J")).getNames()).containsExactlyInAnyOrder(
                        "com.xyz.meta.A", "com.xyz.meta.B", "com.xyz.meta.F", "com.xyz.meta.E", "com.xyz.meta.H",
                        "com.xyz.meta.I");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I")
                .union(scanResult.getClassesWithAnnotation("com.xyz.meta.J")).directOnly().getNames())
                        .containsExactlyInAnyOrder("com.xyz.meta.F", "com.xyz.meta.E", "com.xyz.meta.H");
    }

    @Test
    public void intersect() {
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I")
                .intersect(scanResult.getClassesWithAnnotation("com.xyz.meta.J")).getNames())
                        .containsExactlyInAnyOrder("com.xyz.meta.E", "com.xyz.meta.B");
        assertThat(scanResult.getClassesWithAnnotation("com.xyz.meta.I")
                .intersect(scanResult.getClassesWithAnnotation("com.xyz.meta.J")).directOnly().getNames())
                        .containsExactlyInAnyOrder("com.xyz.meta.E");
    }
}
