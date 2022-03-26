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
package io.github.classgraph.test.methodinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ArrayClassInfo;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList.MethodInfoFilter;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;
import io.github.classgraph.test.external.ExternalAnnotation;

/**
 * MethodInfoTest.
 */
public class MethodInfoTest {
    /**
     * The Class X.
     */
    public static class X extends Exception {
        /***/
        private static final long serialVersionUID = 1L;

        /**
         * Method.
         */
        public void xMethod() {
        }
    }

    /**
     * Public method with args.
     *
     * @param str
     *            the str
     * @param c
     *            the c
     * @param j
     *            the j
     * @param f
     *            the f
     * @param b
     *            the b
     * @param l
     *            the l
     * @param xArray
     *            the x array
     * @param varargs
     *            the varargs
     * @return the int
     */
    @ExternalAnnotation
    public final int publicMethodWithArgs(final String str, final char c, final long j, final float[] f,
            final byte[][] b, final List<Float> l, final X[][][] xArray, final String[]... varargs) {
        return 0;
    }

    /**
     * Private method.
     *
     * @return the string[]
     */
    @SuppressWarnings("unused")
    private static String[] privateMethod() {
        return null;
    }

    public void throwsException() throws X {
    }

    public <X2 extends X> void throwsGenericException() throws X, X2 {
    }

    /**
     * Method info not enabled.
     */
    @Test
    public void methodInfoNotEnabled() {
        // .enableSaveMethodInfo() not called
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .scan()) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo());
        }
    }

    /**
     * Get method info.
     */
    @Test
    public void testGetMethodInfo() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .filter(new MethodInfoFilter() {
                        @Override
                        public boolean accept(final MethodInfo methodInfo) {
                            // JDK 10 fix
                            return !methodInfo.getName().equals("$closeResource")
                                    && !methodInfo.getName().equals("lambda$0") && !methodInfo.isSynthetic();
                        }
                    }).getAsStrings()).containsOnly( //
                            "@" + ExternalAnnotation.class.getName() //
                                    + " public final int publicMethodWithArgs(final java.lang.String str, "
                                    + "final char c, final long j, final float[] f, final byte[][] b, "
                                    + "final java.util.List<java.lang.Float> l, " + "final " + X.class.getName()
                                    + "[][][] xArray, " + "final java.lang.String[]... varargs)",
                            "public void throwsException() throws " + X.class.getName(),
                            "public <X2 extends " + X.class.getName() + "> void throwsGenericException() throws "
                                    + X.class.getName() + ", X2",
                            "@" + Test.class.getName() + " public void methodInfoNotEnabled()",
                            "@" + Test.class.getName() + " public void testGetMethodInfo()",
                            "@" + Test.class.getName() + " public void testGetConstructorInfo()",
                            "@" + Test.class.getName() + " public void testGetMethodInfoIgnoringVisibility()",
                            "@" + Test.class.getName() + " public void testGetThrownExceptions()",
                            "@" + Test.class.getName() + " public void testMethodInfoLoadMethodForArrayArg()");
        }
    }

    /**
     * Get constructor info.
     */
    @Test
    public void testGetConstructorInfo() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .enableMethodInfo().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getConstructorInfo().getAsStrings())
                    .containsOnly("public <init>()");
        }
    }

    /**
     * Get method info ignoring visibility.
     */
    @Test
    public void testGetMethodInfoIgnoringVisibility() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .filter(new MethodInfoFilter() {
                        @Override
                        public boolean accept(final MethodInfo methodInfo) {
                            // JDK 10 fix
                            return !methodInfo.getName().equals("$closeResource")
                                    && !methodInfo.getName().equals("lambda$0") && !methodInfo.isSynthetic();
                        }
                    }).getAsStrings()).containsOnly( //
                            "@" + ExternalAnnotation.class.getName() //
                                    + " public final int publicMethodWithArgs(final java.lang.String str, "
                                    + "final char c, final long j, final float[] f, final byte[][] b, "
                                    + "final java.util.List<java.lang.Float> l, " + "final " + X.class.getName()
                                    + "[][][] xArray, " + "final java.lang.String[]... varargs)",
                            "private static java.lang.String[] privateMethod()",
                            "public void throwsException() throws " + X.class.getName(),
                            "public <X2 extends " + X.class.getName() + "> void throwsGenericException() throws "
                                    + X.class.getName() + ", X2",
                            "@" + Test.class.getName() + " public void methodInfoNotEnabled()",
                            "@" + Test.class.getName() + " public void testGetMethodInfo()",
                            "@" + Test.class.getName() + " public void testGetConstructorInfo()",
                            "@" + Test.class.getName() + " public void testGetMethodInfoIgnoringVisibility()",
                            "@" + Test.class.getName() + " public void testGetThrownExceptions()",
                            "@" + Test.class.getName() + " public void testMethodInfoLoadMethodForArrayArg()");
        }
    }

    /**
     * MethodInfo.loadClassAndGetMethod for arrays argument (#344)
     */
    @Test
    public void testMethodInfoLoadMethodForArrayArg() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            final MethodInfo mi = scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .getSingleMethod("publicMethodWithArgs");
            assertThat(mi).isNotNull();
            assertThatCode(() -> {
                mi.loadClassAndGetMethod();
            }).doesNotThrowAnyException();
            assertThat(mi.loadClassAndGetMethod()).isNotNull();

            // Extract array-typed params from method params
            final List<ArrayClassInfo> arrayClassInfoList = new ArrayList<>();
            for (final MethodParameterInfo mpi : mi.getParameterInfo()) {
                final TypeSignature paramTypeSig = mpi.getTypeSignatureOrTypeDescriptor();
                if (paramTypeSig instanceof ArrayTypeSignature) {
                    arrayClassInfoList.add(((ArrayTypeSignature) paramTypeSig).getArrayClassInfo());
                }
            }
            assertThat(arrayClassInfoList.toString()).isEqualTo("[class float[], class byte[][], " + "class "
                    + X.class.getName() + "[][][], " + "class java.lang.String[][]]");
            final ArrayClassInfo p1 = arrayClassInfoList.get(1);
            assertThat(p1.loadElementClass()).isEqualTo(byte.class);
            assertThat(p1.loadClass()).isEqualTo(byte[][].class);
            assertThat(p1.getElementClassInfo()).isNull();
            assertThat(p1.getNumDimensions()).isEqualTo(2);
            final ArrayClassInfo p2 = arrayClassInfoList.get(2);
            assertThat(p2.loadElementClass()).isEqualTo(X.class);
            assertThat(p2.getElementClassInfo().getName()).isEqualTo(X.class.getName());
            assertThat(p2.loadClass()).isEqualTo(X[][][].class);
            assertThat(p2.getElementClassInfo().getMethodInfo().get(0).getName()).isEqualTo("xMethod");
            assertThat(p2.getNumDimensions()).isEqualTo(3);
            final ArrayClassInfo p3 = arrayClassInfoList.get(3);
            assertThat(p3.loadElementClass()).isEqualTo(String.class);
            assertThat(p3.loadClass()).isEqualTo(String[][].class);
            assertThat(p3.getElementClassInfo()).isNull();
            assertThat(p3.getNumDimensions()).isEqualTo(2);
        }
    }

    @Test
    public void testGetThrownExceptions() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(MethodInfoTest.class.getPackage().getName())
                .enableClassInfo().enableMethodInfo().scan()) {
            MethodInfo mi = scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .getSingleMethod("throwsException");
            assertThat(mi.getThrownExceptions()).hasSize(1);
            assertThat(mi.getThrownExceptions().get(0).getSimpleName()).isEqualTo("X");

            mi = scanResult.getClassInfo(MethodInfoTest.class.getName()).getMethodInfo()
                    .getSingleMethod("throwsGenericException");
            assertThat(mi.getThrownExceptions()).hasSize(2);
            assertThat(mi.getThrownExceptions().get(0).getSimpleName()).isEqualTo("X");
            assertThat(mi.getThrownExceptions().get(1).getSimpleName()).isEqualTo("X");
        }
    }
}
