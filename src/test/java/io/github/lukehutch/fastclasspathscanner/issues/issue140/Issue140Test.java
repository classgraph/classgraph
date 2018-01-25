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
package io.github.lukehutch.fastclasspathscanner.issues.issue140;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.FieldInfo;

public class Issue140Test {
    // Order of fields is significant
    public int intField;
    public String[] stringArrField;

    @Test
    public void issue140Test() throws IOException {
        final ClassInfo ci = new FastClasspathScanner(Issue140Test.class.getPackage().getName()).enableFieldInfo()
                .scan().getClassNameToClassInfo().get(Issue140Test.class.getName());
        assertThat(ci).isNotNull();
        final List<FieldInfo> allFieldInfo = ci.getFieldInfo();
        assertThat(allFieldInfo.size()).isEqualTo(2);
        assertThat(allFieldInfo.get(0).getType()).isEqualTo(int.class);
        assertThat(allFieldInfo.get(1).getType()).isEqualTo(Array.newInstance(String.class, 0).getClass());
    }
}
