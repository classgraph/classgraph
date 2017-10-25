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
package io.github.lukehutch.fastclasspathscanner.issues.issue148;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;

public class Issue148Test {
    @Test
    public void issue148Test() throws IOException {
        final String pkg = Issue148Test.class.getPackage().getName();
        final Map<String, ClassInfo> classNameToClassInfo = new FastClasspathScanner(pkg).scan()
                .getClassNameToClassInfo();

        final List<ClassInfo> classInfo = new ArrayList<>(classNameToClassInfo.values());
        Collections.sort(classInfo, new Comparator<ClassInfo>() {
            @Override
            public int compare(final ClassInfo o1, final ClassInfo o2) {
                return o1.getClassName().compareTo(o2.getClassName());
            }
        });

        final StringBuilder buf = new StringBuilder();
        for (final ClassInfo ci : classNameToClassInfo.values()) {
            buf.append(ci.getClassName() + "|");
            buf.append(ci.isInnerClass() + " " + ci.isAnonymousInnerClass() + " " + ci.isOuterClass() + "|");
            buf.append(ci.getInnerClassNames() + "|");
            buf.append(ci.getOuterClassName() + "|");
            buf.append(ci.getFullyQualifiedContainingMethodName() + "||" //
                    + "");
        }
        final String bufStr = buf.toString().replace(pkg + ".", "");

        assertThat(bufStr) //
                .isEqualTo(
                        "OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$2|true true false|[]|[OuterClass1, OuterClass1$NonStaticInnerClass, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass]|OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass.newAnonymousNonStaticInnerClass||" //
                                + "OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$1|true true false|[]|[OuterClass1, OuterClass1$NonStaticInnerClass, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass]|OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass.newAnonymousStaticInnerClass||" //
                                + "OuterClass1$NonStaticInnerClass|true false true|[OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$1, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$2]|[OuterClass1]|null||" //
                                + "Issue148Test$1|true true false|[]|[Issue148Test]|Issue148Test.issue148Test||" //
                                + "OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass|true false true|[OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$1, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$2]|[OuterClass1, OuterClass1$NonStaticInnerClass]|null||" //
                                + "OuterClass2|false false true|[OuterClass2$1, OuterClass2$2]|[]|null||" //
                                + "Issue148Test|false false true|[Issue148Test$1]|[]|null||" //
                                + "OuterClass1|false false true|[OuterClass1$NonStaticInnerClass, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$1, OuterClass1$NonStaticInnerClass$NonStaticNestedInnerClass$2, OuterClass1$StaticInnerClass]|[]|null||" //
                                + "OuterClass2$1|true true false|[]|[OuterClass2]|OuterClass2.<clinit>||" //
                                + "java.lang.Object|false false false|[]|[]|null||" //
                                + "java.util.Comparator|false false false|[]|[]|null||" //
                                + "OuterClass1$StaticInnerClass|true false false|[]|[OuterClass1]|null||" //
                                + "OuterClass2$2|true true false|[]|[OuterClass2]|OuterClass2.<init>||");
    }
}
