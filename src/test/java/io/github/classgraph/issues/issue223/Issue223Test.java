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
package io.github.classgraph.issues.issue223;

import static org.assertj.core.api.Assertions.assertThat;

import javax.persistence.Entity;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList.ClassInfoFilter;

@Entity
public class Issue223Test {
    /**
     * The Interface InnerInterface.
     */
    public interface InnerInterface {
    }

    /**
     * Test that inner classes are found, and are named using the binary name form.
     */
    @Test
    public void testInnerClasses() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue223Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            // N.B. this anonymous inner class is deliberately not a lambda -- it is itself counted as one of
            // the two inner classes expected below (the other is InnerInterface).
            final var innerClasses = scanResult.getAllClasses().filter(new ClassInfoFilter() {
                @Override
                public boolean accept(final ClassInfo ci) {
                    return ci.isInnerClass();
                }
            });
            assertThat(innerClasses.size()).isEqualTo(2);
            ClassInfo innerInterface = null;
            for (final ClassInfo ci : innerClasses) {
                if (ci.getName().equals(InnerInterface.class.getName())) {
                    innerInterface = ci;
                }
            }
            assertThat(innerInterface).isNotNull();
            if (innerInterface != null) {
                // The name is the binary name ("Outer$Inner"), not the canonical name
                assertThat(innerInterface.getName()).isEqualTo(InnerInterface.class.getName());
                assertThat(innerInterface.getName()).isEqualTo(Issue223Test.class.getName() + "$InnerInterface");
                assertThat(innerInterface.isInterface()).isTrue();
            }
        }
    }
}
