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
package io.github.lukehutch.fastclasspathscanner.issues.issue153;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.ClassRefAnnotation;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.EnumAnnotation;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.FruitEnum;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.NestedAnnotation;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.StringAnnotation;
import io.github.lukehutch.fastclasspathscanner.issues.issue153.Issue153Test.TwoParamAnnotation;
import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationEnumValue;
import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationParamValue;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

@StringAnnotation("classlabel")
@TwoParamAnnotation(value1 = 'x', value2 = { 1, 2, 3 })
@EnumAnnotation(FruitEnum.BANANA)
@NestedAnnotation({ @StringAnnotation("one"), @StringAnnotation("two") })
@ClassRefAnnotation(Issue153Test.class)
public class Issue153Test {

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface StringAnnotation {
        public String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface TwoParamAnnotation {
        public char value1();

        public int[] value2();
    }

    public enum FruitEnum {
        APPLE, BANANA;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface EnumAnnotation {
        public FruitEnum value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface NestedAnnotation {
        public StringAnnotation[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface ClassRefAnnotation {
        public Class<?> value();
    }

    @StringAnnotation("fieldlabel")
    public static final FruitEnum testField = FruitEnum.BANANA;

    @StringAnnotation("methodlabel")
    public void testMethod() {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface AnnotationWithAndWithoutDefaultValue {
        public String valueWithoutDefault();

        public int valueWithDefault() default 5;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface AnnotationWithOnlyDefaultValue {
        public int value() default 6;
    }

    @AnnotationWithAndWithoutDefaultValue(valueWithoutDefault = "x")
    public static int testFieldWithAndWitoutDefault;

    @AnnotationWithOnlyDefaultValue
    public static int testFieldWithOnlyDefault;

    private static final String pkg = Issue153Test.class.getPackage().getName();

    @Test
    public void classAnnotationParameters() throws IOException {
        final ScanResult scanResult = new FastClasspathScanner(pkg) //
                .enableMethodInfo() //
                .enableFieldInfo() //
                .scan();
        final ClassInfo classInfo = scanResult //
                .getClassNameToClassInfo() //
                .get(Issue153Test.class.getName());

        // Read class annotation parameters
        assertThat(classInfo.getAnnotationInfo().toString()) //
                .isEqualTo("[" //
                        + "@" + StringAnnotation.class.getName() + "(\"classlabel\"), " //
                        + "@" + TwoParamAnnotation.class.getName() + "(value1 = 'x', value2 = {1, 2, 3}), " //
                        + "@" + EnumAnnotation.class.getName() + "(" + FruitEnum.class.getName() + ".BANANA" + "), " //
                        + "@" + NestedAnnotation.class.getName() + "({@" + StringAnnotation.class.getName()
                        + "(\"one\"), " + "@" + StringAnnotation.class.getName() + "(\"two\")}), " //
                        + "@" + ClassRefAnnotation.class.getName() + "(" + Issue153Test.class.getName() + ")" //
                        + "]");

        assertThat(classInfo.getFieldInfo("testField").getAnnotationInfo().toString()) //
                .isEqualTo("[@" + StringAnnotation.class.getName() + "(\"fieldlabel\")]");

        assertThat(classInfo.getMethodInfo("testMethod").get(0).getAnnotationInfo().toString()) //
                .isEqualTo("[@" + StringAnnotation.class.getName() + "(\"methodlabel\")]");

        assertThat(classInfo.getFieldInfo("testFieldWithAndWitoutDefault").getAnnotationInfo().toString()) //
                .isEqualTo("[@" + AnnotationWithAndWithoutDefaultValue.class.getName()
                        + "(valueWithDefault = 5, valueWithoutDefault = \"x\")]");

        assertThat(classInfo.getFieldInfo("testFieldWithOnlyDefault").getAnnotationInfo().toString()) //
                .isEqualTo("[@" + AnnotationWithOnlyDefaultValue.class.getName() + "(6)]");

        // Make sure enum constants can be instantiated
        final AnnotationInfo annotation2 = classInfo.getAnnotationInfo().get(2);
        final AnnotationParamValue annotationParam0 = annotation2.getAnnotationParamValues().get(0);
        final Object bananaRef = ((AnnotationEnumValue) annotationParam0.getParamValue()).getEnumValueRef();
        assertThat(bananaRef.getClass()).isEqualTo(FruitEnum.class);
        assertThat(bananaRef.toString()).isEqualTo(FruitEnum.BANANA.toString());
    }
}
