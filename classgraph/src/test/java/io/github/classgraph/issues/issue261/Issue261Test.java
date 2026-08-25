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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.issues.issue261;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue261Test {
    /**
     * The Class SuperSuperCls.
     */
    private static class SuperSuperCls {
    }

    /**
     * The Class SuperCls.
     */
    private static class SuperCls extends SuperSuperCls {
    }

    /**
     * The Class Cls.
     */
    private static class Cls extends SuperCls {
    }

    @Test
    public void issue261Test() {
        // Accept only the class Cls, so that SuperCls and SuperSuperCls are external classes
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Cls.class.getName()).enableAllInfo()
                .scan()) {
            // Looking upwards through the hierarchy leaves out the external classes too, since
            // enableExternalClasses() was not called
            assertThat(scanResult.getAllSuperclasses(Cls.class).getNames()).isEmpty();

            // Looking downwards can only report what was scanned, so only accepted classes are included: SuperCls
            // is external, and is left out
            assertThat(scanResult.getAllSubclasses(SuperSuperCls.class).getNames())
                    .containsOnly(Cls.class.getName());
        }
    }

    @Test
    public void issue261TestWithExternalClassesEnabled() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Cls.class.getName()).enableAllInfo()
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAllSuperclasses(Cls.class).getNames()).contains(SuperCls.class.getName(),
                    SuperSuperCls.class.getName());
            assertThat(scanResult.getAllSubclasses(SuperSuperCls.class).getNames())
                    .containsOnly(SuperCls.class.getName(), Cls.class.getName());
        }
    }
}
