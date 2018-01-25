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

        final List<ClassInfo> classInfoSorted = new ArrayList<>(classNameToClassInfo.values());
        Collections.sort(classInfoSorted, new Comparator<ClassInfo>() {
            @Override
            public int compare(final ClassInfo o1, final ClassInfo o2) {
                return o1.getClassName().compareTo(o2.getClassName());
            }
        });

        final StringBuilder buf = new StringBuilder();
        for (final ClassInfo ci : classInfoSorted) {
            buf.append(ci.getClassName() + "|");
            buf.append(ci.isInnerClass() + " " + ci.isAnonymousInnerClass() + " " + ci.isOuterClass() + "|");
            buf.append(ci.getInnerClassNames() + "|");
            buf.append(ci.getOuterClassName() + "|");
            buf.append(ci.getFullyQualifiedContainingMethodName() + "\n" //
                    + "");
        }
        final String bufStr = buf.toString().replace(pkg + ".", "");

        // System.out.println("\"" + bufStr.replace("\n", "\\n\" //\n+\"") + "\"");

        assertThat(bufStr) //
                .isEqualTo("Issue148Test|false false true|[Issue148Test$1]|[]|null\n" //
                        + "Issue148Test$1|true true false|[]|[Issue148Test]|Issue148Test.issue148Test\n" //
                        + "O1|false false true|[O1$I, O1$I$II, O1$I$II$1, O1$I$II$2, O1$SI]|[]|null\n" //
                        + "O1$I|true false true|[O1$I$II, O1$I$II$1, O1$I$II$2]|[O1]|null\n" //
                        + "O1$I$II|true false true|[O1$I$II$1, O1$I$II$2]|[O1, O1$I]|null\n" //
                        + "O1$I$II$1|true true false|[]|[O1, O1$I, O1$I$II]|O1$I$II.newSI\n" //
                        + "O1$I$II$2|true true false|[]|[O1, O1$I, O1$I$II]|O1$I$II.newI\n" //
                        + "O1$SI|true false false|[]|[O1]|null\n" //
                        + "O2|false false true|[O2$1, O2$2]|[]|null\n" //
                        + "O2$1|true true false|[]|[O2]|O2.<clinit>\n" //
                        + "O2$2|true true false|[]|[O2]|O2.<init>\n" //
                        + "java.lang.Object|false false false|[]|[]|null\n" //
                        + "java.util.Comparator|false false false|[]|[]|null\n");
    }
}
