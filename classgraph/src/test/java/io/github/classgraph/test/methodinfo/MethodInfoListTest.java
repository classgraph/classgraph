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
package io.github.classgraph.test.methodinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/** Tests for {@link io.github.classgraph.MethodInfoList}. */
public class MethodInfoListTest {
    /** A superclass declaring two overloads of the same method name. */
    public static class Base {
        /** An overloaded method. */
        public void overloaded() {
        }

        /**
         * An overloaded method.
         *
         * @param i
         *            an int.
         */
        public void overloaded(final int i) {
        }
    }

    /** A subclass declaring a method of its own, so that it sorts ahead of the inherited overloads. */
    public static class Sub extends Base {
        /** A method declared only by the subclass. */
        public void declaredBySubclassOnly() {
        }
    }

    /**
     * The exception thrown for an overloaded method name must name the class that declares the overloads, not
     * whichever class happens to declare the first method in the list.
     */
    @Test
    public void getSingleMethodNamesTheDeclaringClassOfTheOverloads() {
        try (var scanResult = new ClassGraph().acceptPackages(MethodInfoListTest.class.getPackage().getName())
                .enableMethodInfo().scan()) {
            final var methodInfo = scanResult.getClassInfo(Sub.class.getName()).getMethodInfo();
            // The first method in the list is declared by Sub, but the overloads are declared by Base
            assertThat(methodInfo.get(0).getClassName()).isEqualTo(Sub.class.getName());
            assertThatThrownBy(() -> methodInfo.getSingleMethod("overloaded"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("There are multiple methods named \"overloaded\" in class " + Base.class.getName());
        }
    }
}
