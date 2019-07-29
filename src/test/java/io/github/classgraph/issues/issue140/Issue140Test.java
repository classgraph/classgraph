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
package io.github.classgraph.issues.issue140;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.BaseTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * Issue140Test.
 */
public class Issue140Test {
    /** The int field. */
    // Order of fields is significant
    public int intField;

    /** The string arr field. */
    public String[] stringArrField;

    /**
     * Issue 140 test.
     */
    @Test
    public void issue140Test() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue140Test.class.getPackage().getName())
                .enableFieldInfo().scan()) {
            final ClassInfo ci = scanResult.getClassInfo(Issue140Test.class.getName());
            assertThat(ci).isNotNull();
            final FieldInfoList allFieldInfo = ci.getFieldInfo();
            assertThat(allFieldInfo.size()).isEqualTo(2);
            final TypeSignature type0 = allFieldInfo.get(0).getTypeSignatureOrTypeDescriptor();
            assertThat(type0).isInstanceOf(BaseTypeSignature.class);
            assertThat(((BaseTypeSignature) type0).getType()).isEqualTo(int.class);
            final TypeSignature type1 = allFieldInfo.get(1).getTypeSignatureOrTypeDescriptor();
            assertThat(type1).isInstanceOf(ArrayTypeSignature.class);
            assertThat(((ArrayTypeSignature) type1).getNumDimensions()).isEqualTo(1);
            final TypeSignature elementTypeSignature = ((ArrayTypeSignature) type1).getElementTypeSignature();
            assertThat(elementTypeSignature).isInstanceOf(ClassRefTypeSignature.class);
            final ClassRefTypeSignature classRefTypeSignature = (ClassRefTypeSignature) elementTypeSignature;
            assertThat(classRefTypeSignature.getBaseClassName()).isEqualTo(String.class.getName());
            assertThat(classRefTypeSignature.loadClass()).isEqualTo(String.class);
        }
    }
}
