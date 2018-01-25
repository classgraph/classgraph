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
package com.xyz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.xyz.meta.A;
import com.xyz.meta.B;
import com.xyz.meta.C;
import com.xyz.meta.D;
import com.xyz.meta.E;
import com.xyz.meta.F;
import com.xyz.meta.G;
import com.xyz.meta.H;
import com.xyz.meta.I;
import com.xyz.meta.J;
import com.xyz.meta.K;
import com.xyz.meta.L;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class MetaAnnotationTest {
    ScanResult scanResult = new FastClasspathScanner("com.xyz.meta").scan();

    @Test
    public void oneLevel() {
        assertThat(scanResult.getNamesOfClassesWithAnnotation(E.class)).containsOnly(B.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(F.class)).containsOnly(B.class.getName(),
                A.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(G.class)).containsOnly(C.class.getName());
    }

    @Test
    public void twoLevels() {
        assertThat(scanResult.getNamesOfClassesWithAnnotation(I.class)).containsOnly(B.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(J.class)).containsOnly(B.class.getName(),
                A.class.getName());
    }

    @Test
    public void threeLevels() {
        // for (Class<?> c : new Class<?>[] { A.class, B.class, C.class }) { System.out.println(c.getSimpleName() +
        // " " + scanner.getNamesOfAnnotationsOnClass(c)); } for (Class<?> c : new Class<?>[] { D.class, E.class,
        // F.class, G.class, H.class, I.class, J.class, K.class, L.class }) { System.out.println(c.getSimpleName() +
        // " " + scanner.getNamesOfMetaAnnotationsOnAnnotation(c)); }
        assertThat(scanResult.getNamesOfClassesWithAnnotation(L.class)).containsOnly(B.class.getName());
    }

    @Test
    public void acrossCycle() {
        assertThat(scanResult.getNamesOfClassesWithAnnotation(H.class)).containsOnly(B.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(K.class)).containsOnly(B.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(D.class)).containsOnly(B.class.getName());
        assertThat(scanResult.getNamesOfAnnotationsOnClass(B.class)).containsOnly(E.class.getName(),
                F.class.getName(), H.class.getName(), I.class.getName(), J.class.getName(), K.class.getName(),
                L.class.getName(), D.class.getName());
    }

    @Test
    public void namesOfMetaAnnotations() {
        assertThat(scanResult.getNamesOfAnnotationsOnClass(A.class)).containsOnly(J.class.getName(),
                F.class.getName());
        assertThat(scanResult.getNamesOfAnnotationsOnClass(C.class)).containsOnly(G.class.getName());
    }

    @Test
    public void annotationsAnyOf() {
        assertThat(scanResult.getNamesOfClassesWithAnnotationsAnyOf(J.class, G.class))
                .containsOnly(B.class.getName(), A.class.getName(), C.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class, G.class)).isEmpty();
        assertThat(scanResult.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class))
                .containsOnly(B.class.getName());
    }
}
