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
package io.github.classgraph.issues.issue238;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.persistence.Entity;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue238Test.
 */
@Entity
public class Issue238Test {
    /**
     * The Class B.
     */
    public static class B extends D {
    }

    /**
     * The Class C.
     */
    public static class C {
    }

    /**
     * The Class D.
     */
    public static class D extends C {
    }

    /**
     * The Class E.
     */
    public static class E extends F {
    }

    /**
     * The Class A.
     */
    public static class A extends G {
    }

    /**
     * The Class G.
     */
    public static class G extends B {
    }

    /**
     * The Class F.
     */
    public static class F extends A {
    }

    /**
     * Test superclass inheritance order.
     */
    @Test
    public void testSuperclassInheritanceOrder() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue238Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            final List<String> classNames = scanResult.getAllClasses().get(E.class.getName()).getSuperclasses()
                    .getNames();
            assertThat(classNames).containsExactly(F.class.getName(), A.class.getName(), G.class.getName(),
                    B.class.getName(), D.class.getName(), C.class.getName());
        }
    }
}
