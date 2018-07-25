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
package io.github.lukehutch.fastclasspathscanner.issues.issue140;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.ArrayTypeSignature;
import io.github.lukehutch.fastclasspathscanner.BaseTypeSignature;
import io.github.lukehutch.fastclasspathscanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.ClassRefTypeSignature;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.FieldInfoList;
import io.github.lukehutch.fastclasspathscanner.TypeSignature;

public class Issue140Test {
    // Order of fields is significant
    public int intField;
    public String[] stringArrField;

    @Test
    public void issue140Test() throws IOException {
        final ClassInfo ci = new FastClasspathScanner().whitelistPackages(Issue140Test.class.getPackage().getName())
                .enableFieldInfo().scan().getClassInfo(Issue140Test.class.getName());
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
