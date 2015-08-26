/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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

package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import org.junit.Test;

public class MetaAnnotationTest {
    FastClasspathScanner scanner = new FastClasspathScanner(getClass().getPackage().getName()).scan();

    @Test
    public void oneLevel() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(E.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(F.class)).containsOnly(A.class.getName(),
                B.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(G.class)).containsOnly(C.class.getName());
    }

    @Test
    public void twoLevels() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(I.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(J.class)).containsOnly(A.class.getName(),
                B.class.getName());
    }

    @Test
    public void threeLevels() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(L.class)).containsOnly(A.class.getName());
    }

    @Test
    public void acrossCycle() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(H.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(K.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(M.class)).containsOnly(A.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(A.class)).containsOnly(E.class.getName(),
                F.class.getName(), H.class.getName(), I.class.getName(), J.class.getName(), K.class.getName(),
                L.class.getName(), M.class.getName());
    }

    @Test
    public void namesOfMetaAnnotations() {
        assertThat(scanner.getNamesOfAnnotationsOnClass(B.class))
                .containsOnly(J.class.getName(), F.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(C.class)).containsOnly(G.class.getName());
        assertThat(scanner.getNamesOfAnnotationsOnClass(D.class)).isEmpty();
    }

    @Test
    public void annotationsAnyOf() {
        assertThat(scanner.getNamesOfClassesWithAnnotationsAnyOf(J.class, G.class)).containsOnly(A.class.getName(),
                B.class.getName(), C.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class, G.class)).isEmpty();
        assertThat(scanner.getNamesOfClassesWithAnnotationsAllOf(I.class, J.class)).containsOnly(A.class.getName());
    }
}
