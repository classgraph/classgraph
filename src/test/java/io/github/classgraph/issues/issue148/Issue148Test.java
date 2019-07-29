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
package io.github.classgraph.issues.issue148;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue148Test.
 */
public class Issue148Test {
    /** The anonymous inner class 1. */
    final Runnable anonymousInnerClass1 = new Runnable() {
        @Override
        public void run() {
        }
    };

    /**
     * Issue 148 test.
     */
    @Test
    public void issue148Test() {
        final Runnable anonymousInnerClass2 = new Runnable() {
            @Override
            public void run() {
            }
        };
        // Fix FindBugs warning (dead store to anonymousInnerClass2)
        @SuppressWarnings("unused")
        final String s = anonymousInnerClass2.toString();

        final String pkg = Issue148Test.class.getPackage().getName();
        final StringBuilder buf = new StringBuilder();
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(pkg).enableAllInfo().scan()) {
            for (final ClassInfo ci : scanResult.getAllClasses()) {
                buf.append(ci.getName()).append("|");
                buf.append(ci.isInnerClass()).append(" ").append(ci.isAnonymousInnerClass()).append(" ")
                        .append(ci.isOuterClass()).append("|");
                buf.append(ci.getInnerClasses().getNames()).append("|");
                buf.append(ci.getOuterClasses().getNames()).append("|");
                buf.append(ci.getFullyQualifiedDefiningMethodName()).append("\n");
            }
        }
        final String bufStr = buf.toString().replace(pkg + ".", "");

        // System.out.println("\"" + bufStr.replace("\n", "\\n\" //\n+\"") + "\"");

        assertThat(bufStr) //
                .isEqualTo("Issue148Test|false false true|[Issue148Test$1, Issue148Test$2]|[]|null\n"
                        + "Issue148Test$1|true true false|[]|[Issue148Test]|Issue148Test.<clinit>\n"
                        + "Issue148Test$2|true true false|[]|[Issue148Test]|Issue148Test.issue148Test\n"
                        + "O1|false false true|[O1$I, O1$I$II, O1$I$II$1, O1$I$II$2, O1$SI]|[]|null\n"
                        + "O1$I|true false true|[O1$I$II, O1$I$II$1, O1$I$II$2]|[O1]|null\n"
                        + "O1$I$II|true false true|[O1$I$II$1, O1$I$II$2]|[O1$I, O1]|null\n"
                        + "O1$I$II$1|true true false|[]|[O1$I$II, O1$I, O1]|O1$I$II.newSI\n"
                        + "O1$I$II$2|true true false|[]|[O1$I$II, O1$I, O1]|O1$I$II.newI\n"
                        + "O1$SI|true false false|[]|[O1]|null\n" + "O2|false false true|[O2$1, O2$2]|[]|null\n"
                        + "O2$1|true true false|[]|[O2]|O2.<clinit>\n"
                        + "O2$2|true true false|[]|[O2]|O2.<init>\n");
    }
}
