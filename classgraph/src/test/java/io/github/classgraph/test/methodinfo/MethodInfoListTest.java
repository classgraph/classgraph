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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList;
import io.github.classgraph.ScanResult;

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

    /** The scan result for the package this test is in. */
    private static ScanResult scanResult;

    /** The methods of {@link Sub}, which are one method of its own plus the two overloads it inherits. */
    private static MethodInfoList methodInfo;

    /** Scan the package this test is in. */
    @BeforeAll
    public static void scan() {
        scanResult = new ClassGraph().acceptPackages(MethodInfoListTest.class.getPackageName()).enableMethodInfo()
                .scan();
        methodInfo = scanResult.getClassInfo(Sub.class.getName()).getMethodInfo();
    }

    /** Close the scan result. */
    @AfterAll
    public static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The exception thrown for an overloaded method name must name the class that declares the overloads, not
     * whichever class happens to declare the first method in the list.
     */
    @Test
    public void getSingleMethodNamesTheDeclaringClassOfTheOverloads() {
        // The first method in the list is declared by Sub, but the overloads are declared by Base
        assertThat(methodInfo.get(0).getClassName()).isEqualTo(Sub.class.getName());
        assertThatThrownBy(() -> methodInfo.getSingleMethod("overloaded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("There are multiple methods named \"overloaded\" in class " + Base.class.getName());
    }

    /** A method name that is declared once is returned by {@link MethodInfoList#getSingleMethod(String)}. */
    @Test
    public void getSingleMethodReturnsTheOnlyMethodWithAName() {
        assertThat(methodInfo.getSingleMethod("declaredBySubclassOnly").getName())
                .isEqualTo("declaredBySubclassOnly");
        assertThat(methodInfo.getSingleMethod("noSuchMethod")).isNull();
    }

    /** The list can be asked whether it holds a method of a given name. */
    @Test
    public void theListKnowsWhichMethodNamesItHolds() {
        assertThat(methodInfo.containsName("declaredBySubclassOnly")).isTrue();
        // A name shared by several overloads is still a name the list holds
        assertThat(methodInfo.containsName("overloaded")).isTrue();
        assertThat(methodInfo.containsName("noSuchMethod")).isFalse();
        // The name is matched against the method name, not the method's descriptor or the name of its class
        assertThat(methodInfo.containsName(Sub.class.getName())).isFalse();
    }

    /** Looking a name up returns every overload with that name, and the empty list if there are none. */
    @Test
    public void lookingUpANameReturnsEveryOverloadWithThatName() {
        assertThat(methodInfo.get("overloaded")).hasSize(2)
                .allSatisfy(mi -> assertThat(mi.getName()).isEqualTo("overloaded"));
        assertThat(methodInfo.get("declaredBySubclassOnly")).hasSize(1);
        assertThat(methodInfo.get("noSuchMethod")).isEmpty();
    }

    /** The list can be viewed as a map from method name to the overloads with that name. */
    @Test
    public void theListCanBeViewedAsAMapFromNameToOverloads() {
        final var asMap = methodInfo.asMap();
        assertThat(asMap).containsOnlyKeys("declaredBySubclassOnly", "overloaded");
        assertThat(asMap.get("overloaded")).hasSize(2);
        assertThat(asMap.get("declaredBySubclassOnly")).hasSize(1);
    }

    /** A filtered list holds only the methods the predicate accepted. */
    @Test
    public void filteringKeepsOnlyTheMethodsThePredicateAccepts() {
        assertThat(methodInfo.filter(MethodInfo::isPublic)).hasSameSizeAs(methodInfo);
        assertThat(methodInfo.filter(mi -> !mi.getParameterInfo().isEmpty()).getNames())
                .containsExactly("overloaded");
        assertThat(methodInfo.filter(mi -> false)).isEmpty();
    }

    /** The shared empty list is empty, and holds no method of any name. */
    @Test
    public void theEmptyListIsEmpty() {
        assertThat(MethodInfoList.emptyList()).isEmpty();
        assertThat(MethodInfoList.emptyList().containsName("overloaded")).isFalse();
        assertThat(MethodInfoList.emptyList().getSingleMethod("overloaded")).isNull();
        assertThat(MethodInfoList.emptyList().asMap()).isEmpty();
    }
}
