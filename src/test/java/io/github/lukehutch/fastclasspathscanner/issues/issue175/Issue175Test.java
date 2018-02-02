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
package io.github.lukehutch.fastclasspathscanner.issues.issue175;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.MethodInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Issue175Test {
    @Test
    public void testSynthetic() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-has-kotlin-enum.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("net.corda.core.contracts") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan();

        final Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();

        final List<String> methods = new ArrayList<>();
        for (final String className : result.getNamesOfAllClasses()) {
            final ClassInfo classInfo = allInfo.get(className);
            for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                methods.add(method.toString());
            }
        }
        assertThat(methods).containsOnly("static void <clinit>()",
                "protected <init>(synthetic java.lang.String $enum$name, synthetic int $enum$ordinal)",
                "public static net.corda.core.contracts.ComponentGroupEnum[] values()",
                "public static net.corda.core.contracts.ComponentGroupEnum valueOf(java.lang.String)");
    }

    @Test
    public void testMandated() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-parameter-arity-mismatch.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("net.corda.core") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan();

        final Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();

        final List<String> methods = new ArrayList<>();
        for (final String className : result.getNamesOfAllClasses()) {
            final ClassInfo classInfo = allInfo.get(className);
            for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                methods.add(method.toString());
            }
        }
        assertThat(methods).containsOnly(
                "@org.jetbrains.annotations.NotNull public static final <A> rx.Observable<A> toObservable("
                        + "@org.jetbrains.annotations.NotNull mandated "
                        + "net.corda.core.concurrent.CordaFuture<? extends A> $receiver)",
                "@org.jetbrains.annotations.NotNull public static final <T> "
                        + "net.corda.core.concurrent.CordaFuture<T> toFuture(@org.jetbrains.annotations.NotNull "
                        + "mandated rx.Observable<T> $receiver)");
    }

    @Test
    public void testMismatchedTypes() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-type-signature-mismatch.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("net.corda.core") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan();

        final Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();

        final List<String> methods = new ArrayList<>();
        for (final String className : result.getNamesOfAllClasses()) {
            final ClassInfo classInfo = allInfo.get(className);
            for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                methods.add(method.toString());
            }
        }
        assertThat(methods).containsOnly(
                "public static final <V, W> W match(@org.jetbrains.annotations.NotNull mandated "
                        + "java.util.concurrent.Future<V> $receiver, @org.jetbrains.annotations.NotNull "
                        + "kotlin.jvm.functions.Function1<? super V, ? extends W> success, "
                        + "@org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? "
                        + "super java.lang.Throwable, ? extends W> failure)",
                "@org.jetbrains.annotations.NotNull public static final <V, W> "
                        + "net.corda.core.concurrent.CordaFuture<W> firstOf(@org.jetbrains.annotations.NotNull "
                        + "net.corda.core.concurrent.CordaFuture<? extends V>[] futures, "
                        + "@org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super "
                        + "net.corda.core.concurrent.CordaFuture<? extends V>, ? extends W> handler)",
                "public static void shortCircuitedTaskFailedMessage$annotations()",
                "@org.jetbrains.annotations.NotNull public static final <V, W> "
                        + "net.corda.core.concurrent.CordaFuture<W> firstOf(@org.jetbrains.annotations.NotNull "
                        + "net.corda.core.concurrent.CordaFuture<? extends V>[] futures, "
                        + "@org.jetbrains.annotations.NotNull org.slf4j.Logger log, "
                        + "@org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super "
                        + "net.corda.core.concurrent.CordaFuture<? extends V>, ? extends W> handler)",
                "static void <clinit>()");
    }

    @Test
    public void testResultTypesNotReconciled1() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-corresponding-type-parameters-do-not-refer-to-the same-bare-types.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("net.corda.core.contracts") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan();

        final Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();

        final List<String> methods = new ArrayList<>();
        for (final String className : result.getNamesOfAllClasses()) {
            final ClassInfo classInfo = allInfo.get(className);
            for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                methods.add(method.toString());
            }
        }
    }

    @Test
    public void testResultTypesNotReconciled2() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-result-types-couldnt-be-reconciled.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("net.corda.testing.node") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan();

        final Map<String, ClassInfo> allInfo = result.getClassNameToClassInfo();

        final List<String> methods = new ArrayList<>();
        for (final String className : result.getNamesOfAllClasses()) {
            final ClassInfo classInfo = allInfo.get(className);
            for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                methods.add(method.toString());
            }
        }
    }
}
