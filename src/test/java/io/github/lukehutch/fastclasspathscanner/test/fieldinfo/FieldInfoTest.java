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
package io.github.lukehutch.fastclasspathscanner.test.fieldinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.FieldInfo;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;

public class FieldInfoTest {
    @ExternalAnnotation
    public final int publicFieldWithAnnotation = 3;

    @ExternalAnnotation
    private final String privateFieldWithAnnotation = "test";

    public int fieldWithoutAnnotation;

    @Test
    public void fieldInfoNotEnabled() throws Exception {
        try {
            // .enableSaveFieldInfo() not called
            new FastClasspathScanner(FieldInfoTest.class.getPackage().getName()).scan().getClassNameToClassInfo()
                    .get(FieldInfoTest.class.getName()).getFieldInfo();
            throw new RuntimeException("Fail");
        } catch (final Exception e) {
            // Pass
        }
    }

    @Test
    public void getFieldInfo() throws Exception {
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(
                FieldInfoTest.class.getPackage().getName()).enableFieldInfo().scan().getClassNameToClassInfo();
        final List<String> fieldInfoStrs = new ArrayList<>();
        for (final FieldInfo fieldInfo : classNameToClassInfo.get(FieldInfoTest.class.getName()).getFieldInfo()) {
            fieldInfoStrs.add(fieldInfo.toString());
        }
        assertThat(fieldInfoStrs).containsOnly(
                "@" + ExternalAnnotation.class.getName() + " public final int publicFieldWithAnnotation = 3",
                "public int fieldWithoutAnnotation");
    }

    @Test
    public void getFieldInfoIgnoringVisibility() throws Exception {
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(
                FieldInfoTest.class.getPackage().getName()).enableFieldInfo().ignoreFieldVisibility().scan()
                        .getClassNameToClassInfo();
        final List<String> fieldInfoStrs = new ArrayList<>();
        for (final FieldInfo fieldInfo : classNameToClassInfo.get(FieldInfoTest.class.getName()).getFieldInfo()) {
            fieldInfoStrs.add(fieldInfo.toString());
        }
        assertThat(fieldInfoStrs).containsOnly(
                "@" + ExternalAnnotation.class.getName() + " public final int publicFieldWithAnnotation = 3",
                "@" + ExternalAnnotation.class.getName()
                        + " private final java.lang.String privateFieldWithAnnotation = \"test\"",
                "public int fieldWithoutAnnotation");
    }
}
