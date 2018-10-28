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
package io.github.classgraph.issues.issue153;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.Test;

import io.github.classgraph.AnnotationEnumValue;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationParameterValue;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.issues.issue153.Issue153Test.ClassRefAnnotation;
import io.github.classgraph.issues.issue153.Issue153Test.EnumAnnotation;
import io.github.classgraph.issues.issue153.Issue153Test.FruitEnum;
import io.github.classgraph.issues.issue153.Issue153Test.NestedAnnotation;
import io.github.classgraph.issues.issue153.Issue153Test.StringAnnotation;
import io.github.classgraph.issues.issue153.Issue153Test.TwoParamAnnotation;

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
        try (ScanResult scanResult = new ClassGraph() //
                .whitelistPackages(pkg) //
                .enableMethodInfo() //
                .enableFieldInfo() //
                .enableAnnotationInfo() //
                .scan()) {
            final ClassInfo classInfo = scanResult //
                    .getClassInfo(Issue153Test.class.getName());

            // Read class annotation parameters
            assertThat(classInfo.getAnnotationInfo().getAsStrings()) //
                    .containsExactlyInAnyOrder("@" + StringAnnotation.class.getName() + "(\"classlabel\")", //
                            "@" + TwoParamAnnotation.class.getName() + "(value1 = 'x', value2 = {1, 2, 3})", //
                            "@" + EnumAnnotation.class.getName() + "(" + FruitEnum.class.getName() + ".BANANA"
                                    + ")", //
                            "@" + NestedAnnotation.class.getName() + "({@" + StringAnnotation.class.getName()
                                    + "(\"one\"), @" + StringAnnotation.class.getName() + "(\"two\")})", //
                            "@" + ClassRefAnnotation.class.getName() + "(" + Issue153Test.class.getName() + ")");

            assertThat(classInfo.getFieldInfo("testField").getAnnotationInfo().getAsStrings()) //
                    .containsExactly("@" + StringAnnotation.class.getName() + "(\"fieldlabel\")");

            assertThat(classInfo.getMethodInfo("testMethod").get(0).getAnnotationInfo().getAsStrings()) //
                    .containsExactly("@" + StringAnnotation.class.getName() + "(\"methodlabel\")");

            assertThat(classInfo.getFieldInfo("testFieldWithAndWitoutDefault").getAnnotationInfo().getAsStrings()) //
                    .containsExactly("@" + AnnotationWithAndWithoutDefaultValue.class.getName()
                            + "(valueWithoutDefault = \"x\")");

            assertThat(classInfo.getFieldInfo("testFieldWithOnlyDefault").getAnnotationInfo().getAsStrings()) //
                    .containsExactly("@" + AnnotationWithOnlyDefaultValue.class.getName());

            // Make sure enum constants can be instantiated
            final AnnotationInfo annotation2 = classInfo.getAnnotationInfo().get(EnumAnnotation.class.getName());
            final AnnotationParameterValue annotationParam0 = annotation2.getParameterValues().get(0);
            final Object bananaRef = ((AnnotationEnumValue) annotationParam0.getValue())
                    .loadClassAndReturnEnumValue();
            assertThat(bananaRef.getClass()).isEqualTo(FruitEnum.class);
            assertThat(bananaRef.toString()).isEqualTo(FruitEnum.BANANA.toString());
        }
    }
}
