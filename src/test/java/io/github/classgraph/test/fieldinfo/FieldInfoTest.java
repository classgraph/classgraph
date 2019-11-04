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
package io.github.classgraph.test.fieldinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalAnnotation;

/**
 * FieldInfoTest.
 */
public class FieldInfoTest {
    /** Constant publicFieldWithAnnotation. */
    @ExternalAnnotation
    public static final int publicFieldWithAnnotation = 3;

    /** Constant privateFieldWithAnnotation. */
    @ExternalAnnotation
    private static final String privateFieldWithAnnotation = "test";

    /** Field without annotation. */
    public int fieldWithoutAnnotation;

    /**
     * Field with initializer but without static or final modifier. In Java, the constant pool is not currently used
     * by the compiler to assign initializer values for non-static, non-final fields, whereas it supposedly is in
     * Kotlin (#379).
     */
    public int nonStaticNonFinalFieldWithInitializer = 5;

    /**
     * Static non-final field with initializer (#379).
     */
    public static int staticNonFinalFieldWithInitializer = 7;

    /**
     * Field info not enabled.
     */
    @Test
    public void fieldInfoNotEnabled() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(FieldInfoTest.class.getPackage().getName())
                .scan()) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> scanResult.getClassInfo(FieldInfoTest.class.getName()).getFieldInfo());
        }
    }

    /**
     * Get field info.
     */
    @Test
    public void testGetFieldInfo() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(FieldInfoTest.class.getPackage().getName())
                .enableFieldInfo().enableStaticFinalFieldConstantInitializerValues().enableAnnotationInfo()
                .scan()) {
            final List<String> fieldInfoStrs = scanResult.getClassInfo(FieldInfoTest.class.getName()).getFieldInfo()
                    .getAsStrings();
            assertThat(fieldInfoStrs).containsOnly(
                    "@" + ExternalAnnotation.class.getName()
                            + " public static final int publicFieldWithAnnotation = 3",
                    "public int fieldWithoutAnnotation", "public int nonStaticNonFinalFieldWithInitializer",
                    "public static int staticNonFinalFieldWithInitializer");
        }
    }

    /**
     * Get field info ignoring visibility.
     */
    @Test
    public void testGetFieldInfoIgnoringVisibility() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(FieldInfoTest.class.getPackage().getName())
                .enableFieldInfo().enableStaticFinalFieldConstantInitializerValues().enableAnnotationInfo()
                .ignoreFieldVisibility().scan()) {
            final List<String> fieldInfoStrs = scanResult.getClassInfo(FieldInfoTest.class.getName()).getFieldInfo()
                    .getAsStrings();
            assertThat(fieldInfoStrs).containsOnly(
                    "@" + ExternalAnnotation.class.getName()
                            + " public static final int publicFieldWithAnnotation = 3",
                    "@" + ExternalAnnotation.class.getName()
                            + " private static final java.lang.String privateFieldWithAnnotation = \"test\"",
                    "public int fieldWithoutAnnotation", "public int nonStaticNonFinalFieldWithInitializer",
                    "public static int staticNonFinalFieldWithInitializer");
        }
    }
}
