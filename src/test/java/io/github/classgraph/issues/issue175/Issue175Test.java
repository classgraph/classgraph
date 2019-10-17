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
package io.github.classgraph.issues.issue175;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue175Test.
 */
public class Issue175Test {
    /**
     * Test synthetic.
     */
    @Test
    public void testSynthetic() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-has-kotlin-enum.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core.contracts") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().ignoreMethodVisibility()
                .ignoreFieldVisibility().enableMethodInfo().enableFieldInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly( //
                    "protected <init>(synthetic java.lang.String $enum$name, synthetic int $enum$ordinal)",
                    "public static net.corda.core.contracts.ComponentGroupEnum[] values()",
                    "public static net.corda.core.contracts.ComponentGroupEnum valueOf(java.lang.String)");
        }
    }

    /**
     * Test mandated.
     */
    @Test
    public void testMandated() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-parameter-arity-mismatch.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly(
                    "@org.jetbrains.annotations.NotNull public static final <A> rx.Observable<A> toObservable(@org.jetbrains.annotations.NotNull mandated net.corda.core.concurrent.CordaFuture<? extends A> $receiver)",
                    "@org.jetbrains.annotations.NotNull public static final <T> net.corda.core.concurrent.CordaFuture<T> toFuture(@org.jetbrains.annotations.NotNull mandated rx.Observable<T> $receiver)");
        }
    }

    /**
     * Test mismatched types.
     */
    @Test
    public void testMismatchedTypes() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-type-signature-mismatch.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly(
                    "public static final <V, W> W match(@org.jetbrains.annotations.NotNull mandated java.util.concurrent.Future<V> $receiver, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super V, ? extends W> success, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super java.lang.Throwable, ? extends W> failure)",
                    "@org.jetbrains.annotations.NotNull public static final <V, W> net.corda.core.concurrent.CordaFuture<W> firstOf(@org.jetbrains.annotations.NotNull net.corda.core.concurrent.CordaFuture<? extends V>[] futures, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super net.corda.core.concurrent.CordaFuture<? extends V>, ? extends W> handler)",
                    "public static synthetic void shortCircuitedTaskFailedMessage$annotations()",
                    "@org.jetbrains.annotations.NotNull public static final <V, W> net.corda.core.concurrent.CordaFuture<W> firstOf(@org.jetbrains.annotations.NotNull net.corda.core.concurrent.CordaFuture<? extends V>[] futures, @org.jetbrains.annotations.NotNull org.slf4j.Logger log, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super net.corda.core.concurrent.CordaFuture<? extends V>, ? extends W> handler)");
        }
    }

    /**
     * Test result types not reconciled 1.
     */
    @Test
    public void testResultTypesNotReconciled1() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-corresponding-type-parameters-do-not-refer-to-the same-bare-types.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core.contracts") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly("private final java.lang.String commandDataToString()",
                    "@org.jetbrains.annotations.NotNull public java.lang.String toString()",
                    "@org.jetbrains.annotations.NotNull public final T getValue()",
                    "@org.jetbrains.annotations.NotNull public final java.util.List<java.security.PublicKey> getSigners()",
                    "public <init>(@org.jetbrains.annotations.NotNull T value, @org.jetbrains.annotations.NotNull java.util.List<? extends java.security.PublicKey> signers)",
                    "public <init>(@org.jetbrains.annotations.NotNull T data, @org.jetbrains.annotations.NotNull java.security.PublicKey key)",
                    "@org.jetbrains.annotations.NotNull public final T component1()",
                    "@org.jetbrains.annotations.NotNull public final java.util.List<java.security.PublicKey> component2()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.core.contracts.Command<T> copy(@org.jetbrains.annotations.NotNull T value, @org.jetbrains.annotations.NotNull java.util.List<? extends java.security.PublicKey> signers)",
                    "@org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.core.contracts.Command copy$default(net.corda.core.contracts.Command, net.corda.core.contracts.CommandData, java.util.List, int, java.lang.Object)",
                    "public int hashCode()", //
                    "public boolean equals(java.lang.Object)");
        }
    }

    /**
     * Test result types not reconciled 2.
     */
    @Test
    public void testResultTypesNotReconciled2() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-result-types-couldnt-be-reconciled.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.testing.node") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly("public final int getNextNodeId()",
                    "private final void setNextNodeId(int <set-?>)",
                    "@org.jetbrains.annotations.NotNull public final net.corda.testing.node.InMemoryMessagingNetwork getMessagingNetwork()",
                    "@org.jetbrains.annotations.NotNull public final java.util.List<net.corda.testing.node.MockNetwork$MockNode> getNodes()",
                    "@org.jetbrains.annotations.NotNull public final java.util.List<net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode>> getNotaryNodes()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode> getDefaultNotaryNode()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.core.identity.Party getDefaultNotaryIdentity()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.core.identity.PartyAndCertificate getDefaultNotaryIdentityAndCert()",
                    "private final java.util.List<net.corda.nodeapi.internal.network.NotaryInfo> generateNotaryIdentities()",
                    "@org.jetbrains.annotations.NotNull public java.util.List<net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode>> createNotaries$node_driver_main()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.testing.node.MockNetwork$MockNode createUnstartedNode(@org.jetbrains.annotations.NotNull net.corda.testing.node.MockNodeParameters parameters)",
                    "@org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.testing.node.MockNetwork$MockNode createUnstartedNode$default(net.corda.testing.node.MockNetwork, net.corda.testing.node.MockNodeParameters, int, java.lang.Object)",
                    "@org.jetbrains.annotations.NotNull public final <N extends net.corda.testing.node.MockNetwork$MockNode> N createUnstartedNode(@org.jetbrains.annotations.NotNull net.corda.testing.node.MockNodeParameters parameters, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super net.corda.testing.node.MockNodeArgs, ? extends N> nodeFactory)",
                    "@org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.testing.node.MockNetwork$MockNode createUnstartedNode$default(net.corda.testing.node.MockNetwork, net.corda.testing.node.MockNodeParameters, kotlin.jvm.functions.Function1, int, java.lang.Object)",
                    "@org.jetbrains.annotations.NotNull public final net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode> createNode(@org.jetbrains.annotations.NotNull net.corda.testing.node.MockNodeParameters parameters)",
                    "@org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.node.internal.StartedNode createNode$default(net.corda.testing.node.MockNetwork, net.corda.testing.node.MockNodeParameters, int, java.lang.Object)",
                    "@org.jetbrains.annotations.NotNull public final <N extends net.corda.testing.node.MockNetwork$MockNode> net.corda.node.internal.StartedNode<N> createNode(@org.jetbrains.annotations.NotNull net.corda.testing.node.MockNodeParameters parameters, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super net.corda.testing.node.MockNodeArgs, ? extends N> nodeFactory)",
                    "@org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.node.internal.StartedNode createNode$default(net.corda.testing.node.MockNetwork, net.corda.testing.node.MockNodeParameters, kotlin.jvm.functions.Function1, int, java.lang.Object)",
                    "private final <N extends net.corda.testing.node.MockNetwork$MockNode> N createNodeImpl(net.corda.testing.node.MockNodeParameters parameters, kotlin.jvm.functions.Function1<? super net.corda.testing.node.MockNodeArgs, ? extends N> nodeFactory, boolean start)",
                    "@org.jetbrains.annotations.NotNull public final java.nio.file.Path baseDirectory(int nodeId)",
                    "@kotlin.jvm.JvmOverloads public final void runNetwork(int rounds)",
                    "@kotlin.jvm.JvmOverloads public static synthetic bridge void runNetwork$default(net.corda.testing.node.MockNetwork, int, int, java.lang.Object)",
                    "@kotlin.jvm.JvmOverloads public final void runNetwork()",
                    "@kotlin.jvm.JvmOverloads @org.jetbrains.annotations.NotNull public final net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode> createPartyNode(@org.jetbrains.annotations.Nullable net.corda.core.identity.CordaX500Name legalName)",
                    "@kotlin.jvm.JvmOverloads @org.jetbrains.annotations.NotNull public static synthetic bridge net.corda.node.internal.StartedNode createPartyNode$default(net.corda.testing.node.MockNetwork, net.corda.core.identity.CordaX500Name, int, java.lang.Object)",
                    "@kotlin.jvm.JvmOverloads @org.jetbrains.annotations.NotNull public final net.corda.node.internal.StartedNode<net.corda.testing.node.MockNetwork$MockNode> createPartyNode()",
                    "@org.jetbrains.annotations.NotNull public final net.corda.testing.node.MockNetwork$MockNode addressToNode(@org.jetbrains.annotations.NotNull net.corda.core.messaging.MessageRecipients msgRecipient)",
                    "public final void startNodes()", "public final void stopNodes()",
                    "public final void waitQuiescent()",
                    "public <init>(@org.jetbrains.annotations.NotNull java.util.List<java.lang.String> cordappPackages, @org.jetbrains.annotations.NotNull net.corda.testing.node.MockNetworkParameters defaultParameters, boolean networkSendManuallyPumped, boolean threadPerNode, @org.jetbrains.annotations.NotNull net.corda.testing.node.InMemoryMessagingNetwork$ServicePeerAllocationStrategy servicePeerAllocationStrategy, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super net.corda.testing.node.MockNodeArgs, ? extends net.corda.testing.node.MockNetwork$MockNode> defaultFactory, boolean initialiseSerialization, @org.jetbrains.annotations.NotNull java.util.List<net.corda.testing.node.MockNetwork$NotarySpec> notarySpecs)",
                    "public synthetic <init>(java.util.List, net.corda.testing.node.MockNetworkParameters, boolean, boolean, net.corda.testing.node.InMemoryMessagingNetwork$ServicePeerAllocationStrategy, kotlin.jvm.functions.Function1, boolean, java.util.List, int, kotlin.jvm.internal.DefaultConstructorMarker)",
                    "@kotlin.jvm.JvmOverloads public <init>(@org.jetbrains.annotations.NotNull java.util.List<java.lang.String> cordappPackages, @org.jetbrains.annotations.NotNull net.corda.testing.node.MockNetworkParameters parameters)",
                    "@kotlin.jvm.JvmOverloads public synthetic <init>(java.util.List, net.corda.testing.node.MockNetworkParameters, int, kotlin.jvm.internal.DefaultConstructorMarker)",
                    "@kotlin.jvm.JvmOverloads public <init>(@org.jetbrains.annotations.NotNull java.util.List<java.lang.String>)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic net.corda.nodeapi.internal.network.NetworkParametersCopier access$getNetworkParameters$p(net.corda.testing.node.MockNetwork)",
                    "public static final synthetic boolean access$getThreadPerNode$p(net.corda.testing.node.MockNetwork)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic java.util.List access$getCordappPackages$p(net.corda.testing.node.MockNetwork)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic org.apache.activemq.artemis.utils.ReusableLatch access$getBusyLatch$p(net.corda.testing.node.MockNetwork)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic java.util.concurrent.atomic.AtomicInteger access$getSharedUserCount$p(net.corda.testing.node.MockNetwork)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic net.corda.testing.node.MockNetwork$sharedServerThread$1 access$getSharedServerThread$p(net.corda.testing.node.MockNetwork)");
        }
    }

    /**
     * Test attribute parameter mismatch.
     */
    @Test
    public void testAttributeParameterMismatch() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-attribute-parameter-mismatch.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core.node.services.vault") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly( //
                    "protected <init>(synthetic java.lang.String $enum$name, synthetic int $enum$ordinal, @org.jetbrains.annotations.NotNull java.lang.String columnName)",
                    "public static net.corda.core.node.services.vault.AttachmentSort$AttachmentSortAttribute[] values()",
                    "public static net.corda.core.node.services.vault.AttachmentSort$AttachmentSortAttribute valueOf(java.lang.String)",
                    "@org.jetbrains.annotations.NotNull public final java.lang.String getColumnName()");
        }
    }

    /**
     * Test result type reconciliation issue.
     */
    @Test
    public void testResultTypeReconciliationIssue() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-result-type-could-not-reconcile.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.client.jackson") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly(
                    "@org.jetbrains.annotations.NotNull protected final com.google.common.collect.Multimap<java.lang.String, java.lang.reflect.Method> getMethodMap()",
                    "@org.jetbrains.annotations.NotNull public final java.util.Map<java.lang.String, java.util.List<java.lang.String>> getMethodParamNames()",
                    "@org.jetbrains.annotations.NotNull public java.util.List<java.lang.String> paramNamesFromMethod(@org.jetbrains.annotations.NotNull java.lang.reflect.Method method)",
                    "@org.jetbrains.annotations.NotNull public java.util.List<java.lang.String> paramNamesFromConstructor(@org.jetbrains.annotations.NotNull java.lang.reflect.Constructor<?> ctor)",
                    "@org.jetbrains.annotations.NotNull public final net.corda.client.jackson.StringToMethodCallParser<T>.ParsedMethodCall parse(@org.jetbrains.annotations.Nullable T target, @org.jetbrains.annotations.NotNull java.lang.String command)",
                    "@org.jetbrains.annotations.NotNull public final java.lang.Object[] parseArguments(@org.jetbrains.annotations.NotNull java.lang.String methodNameHint, @org.jetbrains.annotations.NotNull java.util.List<? extends kotlin.Pair<java.lang.String, ? extends java.lang.Class<?>>> parameters, @org.jetbrains.annotations.NotNull java.lang.String args)",
                    "@org.jetbrains.annotations.NotNull public final java.util.Map<java.lang.String, java.lang.String> getAvailableCommands()",
                    "@kotlin.jvm.JvmOverloads public <init>(@org.jetbrains.annotations.NotNull java.lang.Class<? extends T> targetType, @org.jetbrains.annotations.NotNull com.fasterxml.jackson.databind.ObjectMapper om)",
                    "@kotlin.jvm.JvmOverloads public synthetic <init>(java.lang.Class, com.fasterxml.jackson.databind.ObjectMapper, int, kotlin.jvm.internal.DefaultConstructorMarker)",
                    "@kotlin.jvm.JvmOverloads public <init>(@org.jetbrains.annotations.NotNull java.lang.Class<? extends T>)",
                    "public <init>(@org.jetbrains.annotations.NotNull kotlin.reflect.KClass<? extends T> targetType)",
                    "@org.jetbrains.annotations.NotNull public static final synthetic java.util.List access$getIgnoredNames$cp()",
                    "@org.jetbrains.annotations.NotNull public static final synthetic org.slf4j.Logger access$getLog$cp()");
        }
    }

    /**
     * Test parameter arity mismatch.
     */
    @Test
    public void testParameterArityMismatch() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-missing-bare-types.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.core.node.services.vault") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly(
                    "@org.jetbrains.annotations.NotNull public static <Q extends net.corda.core.node.services.vault.GenericQueryCriteria<Q, ? super P>, P extends net.corda.core.node.services.vault.BaseQueryCriteriaParser<Q, ? super P, ? super S>, S extends net.corda.core.node.services.vault.BaseSort> java.util.Collection<javax.persistence.criteria.Predicate> visit(@org.jetbrains.annotations.NotNull net.corda.core.node.services.vault.GenericQueryCriteria$ChainableQueryCriteria$AndVisitor<Q, ? super P, ? super S>, P parser)");
        }
    }

    /**
     * Test bare type issue.
     */
    @Test
    public void testBareTypeIssue() {
        final ClassLoader classLoader = Issue175Test.class.getClassLoader();
        final String aJarName = "issue175-missing-bare-types2.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().whitelistPackages("net.corda.client.jackson") //
                .overrideClassLoaders(overrideClassLoader).ignoreParentClassLoaders().enableAllInfo().scan()) {
            final List<String> methods = new ArrayList<>();
            for (final String className : result.getAllClasses().getNames()) {
                final ClassInfo classInfo = result.getClassInfo(className);
                for (final MethodInfo method : classInfo.getMethodAndConstructorInfo()) {
                    methods.add(method.toString());
                }
            }
            assertThat(methods).containsOnly(
                    "@org.jetbrains.annotations.Nullable public final java.lang.Object invoke()",
                    "@org.jetbrains.annotations.Nullable public java.lang.Object call()",
                    "@org.jetbrains.annotations.NotNull public final java.lang.reflect.Method getMethod()",
                    "@org.jetbrains.annotations.NotNull public final java.lang.Object[] getArgs()",
                    "public <init>(mandated net.corda.client.jackson.StringToMethodCallParser this$0, @org.jetbrains.annotations.Nullable T target, @org.jetbrains.annotations.NotNull java.lang.reflect.Method method, @org.jetbrains.annotations.NotNull java.lang.Object[] args)");
        }
    }
}
