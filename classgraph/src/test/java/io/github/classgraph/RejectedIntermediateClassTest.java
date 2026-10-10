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
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.classgraph.rejectedmiddle.api.Component;
import io.github.classgraph.rejectedmiddle.api.OnStart;
import io.github.classgraph.rejectedmiddle.api.Other;
import io.github.classgraph.rejectedmiddle.api.Service;
import io.github.classgraph.rejectedmiddle.api.TA;
import io.github.classgraph.rejectedmiddle.api.TB;
import io.github.classgraph.rejectedmiddle.app.Impl;
import io.github.classgraph.rejectedmiddle.app.TwoIfaces;
import io.github.classgraph.rejectedmiddle.gen.Base;
import io.github.classgraph.rejectedmiddle.gen.GenIface;

/**
 * A rejected class is left out of the results, but the class graph is still traversed through it, so a query still
 * reaches the accepted classes, interfaces and annotations on the far side of it. The results of a scan that
 * rejects a package are the results of a scan that does not, minus the classes in the rejected package.
 */
public class RejectedIntermediateClassTest {
    /** The package of the fixture classes. */
    private static final String ROOT_PACKAGE = "io.github.classgraph.rejectedmiddle";

    /** The package that is rejected. */
    private static final String REJECTED_PACKAGE = Base.class.getPackage().getName();

    /** The answers of {@link #queryResults(ScanResult)} when {@link Base} is left out of the results. */
    private static final List<String> RESULTS_WITHOUT_BASE = List.of( //
            "getAllClassesImplementing: [" + Impl.class.getName() + "]", //
            "getClassesWithAnnotation: [" + Impl.class.getName() + "]", //
            "getClassesWithMethodAnnotation: [" + Impl.class.getName() + "]", //
            "getAllSuperinterfaces: [" + Service.class.getName() + "]", //
            "getAllAnnotations: [" + Component.class.getName() + "]", //
            "getAllAnnotationInfo: [" + Component.class.getName() + "]", //
            "implementsInterface: true", //
            "hasAnnotation: true", //
            "hasMethodAnnotation: true");

    /**
     * Scan the fixture classes.
     *
     * @param reject
     *            whether to reject the package of {@link Base}.
     * @param enableExternalClasses
     *            whether to enable external classes.
     * @return the scan result.
     */
    private static ScanResult scan(final boolean reject, final boolean enableExternalClasses) {
        var classGraph = new ClassGraph().enableClasspath().enableClassInfo().enableMethodInfo()
                .enableAnnotationInfo().acceptPackages(ROOT_PACKAGE);
        if (reject) {
            classGraph = classGraph.rejectPackages(REJECTED_PACKAGE);
        }
        if (enableExternalClasses) {
            classGraph = classGraph.enableExternalClasses();
        }
        return classGraph.scan();
    }

    /**
     * Run the queries whose answers depend on the class graph around {@link Base}.
     *
     * @param scanResult
     *            the scan result.
     * @return the answer to each query, as a string.
     */
    private static List<String> queryResults(final ScanResult scanResult) {
        final var impl = scanResult.getClassInfo(Impl.class.getName());
        final List<String> results = new ArrayList<>();
        results.add("getAllClassesImplementing: " + scanResult.getAllClassesImplementing(Service.class).getNames());
        results.add("getClassesWithAnnotation: " + scanResult.getClassesWithAnnotation(Component.class).getNames());
        results.add("getClassesWithMethodAnnotation: "
                + scanResult.getClassesWithMethodAnnotation(OnStart.class).getNames());
        results.add("getAllSuperinterfaces: " + impl.getAllSuperinterfaces().getNames());
        results.add("getAllAnnotations: " + impl.getAllAnnotations().getNames());
        results.add("getAllAnnotationInfo: " + impl.getAllAnnotationInfo().getNames());
        results.add("implementsInterface: " + impl.implementsInterface(Service.class));
        results.add("hasAnnotation: " + impl.hasAnnotation(Component.class));
        results.add("hasMethodAnnotation: " + impl.hasMethodAnnotation(OnStart.class));
        return results;
    }

    /**
     * The queries reach the interface, the inherited class annotation and the inherited method annotation of an
     * accepted class through its rejected superclass, and give the answers of the scan that does not reject it,
     * minus the rejected class itself.
     *
     * @param enableExternalClasses
     *            whether to enable external classes.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void queriesReachThroughARejectedSuperclass(final boolean enableExternalClasses) {
        try (var scanResult = scan(/* reject = */ true, enableExternalClasses)) {
            assertThat(queryResults(scanResult)).isEqualTo(RESULTS_WITHOUT_BASE);
        }
        // The same answers, with the rejected class added, when it is not rejected
        try (var scanResult = scan(/* reject = */ false, enableExternalClasses)) {
            assertThat(queryResults(scanResult)).containsExactly( //
                    "getAllClassesImplementing: [" + Impl.class.getName() + ", " + Base.class.getName() + "]", //
                    "getClassesWithAnnotation: [" + Impl.class.getName() + ", " + Base.class.getName() + "]", //
                    "getClassesWithMethodAnnotation: [" + Impl.class.getName() + ", " + Base.class.getName() + "]", //
                    "getAllSuperinterfaces: [" + Service.class.getName() + "]", //
                    "getAllAnnotations: [" + Component.class.getName() + "]", //
                    "getAllAnnotationInfo: [" + Component.class.getName() + "]", //
                    "implementsInterface: true", //
                    "hasAnnotation: true", //
                    "hasMethodAnnotation: true");
        }
    }

    /**
     * The queries reach the same classes through a superclass in a package that is not accepted, which is read only
     * as an external class, and leave it out of the results, as for a rejected superclass.
     */
    @Test
    public void queriesReachThroughAnExternalSuperclass() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableMethodInfo()
                .enableAnnotationInfo()
                .acceptPackages(Service.class.getPackage().getName(), Impl.class.getPackage().getName()).scan()) {
            assertThat(queryResults(scanResult)).isEqualTo(RESULTS_WITHOUT_BASE);
        }
    }

    /**
     * {@link ClassInfo#extendsSuperclass(String)} and {@link ClassInfo#implementsInterface(String)} report the
     * class hierarchy as the JVM sees it, including a rejected superclass and a rejected interface, while the lists
     * of superclasses and interfaces leave the rejected classes out.
     *
     * @param enableExternalClasses
     *            whether to enable external classes.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void predicatesIncludeRejectedSupertypes(final boolean enableExternalClasses) {
        try (var scanResult = scan(/* reject = */ true, enableExternalClasses)) {
            final var impl = scanResult.getClassInfo(Impl.class.getName());
            assertThat(impl.extendsSuperclass(Base.class)).isTrue();
            assertThat(impl.getAllSuperclasses().getNames()).doesNotContain(Base.class.getName());
            final var twoIfaces = scanResult.getClassInfo(TwoIfaces.class.getName());
            assertThat(twoIfaces.implementsInterface(GenIface.class)).isTrue();
            assertThat(twoIfaces.getDirectSuperinterfaces().getNames()).containsExactly(Other.class.getName());
            assertThat(twoIfaces.getAllSuperinterfaces().getNames()).containsExactly(Other.class.getName());
        }
    }

    /**
     * The type descriptor of a class lists a rejected interface, so that each type annotation in the classfile,
     * which names the interface it applies to by its index in the classfile's list of interfaces, stays on the
     * right interface.
     *
     * @param enableExternalClasses
     *            whether to enable external classes.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void typeDescriptorKeepsARejectedInterface(final boolean enableExternalClasses) {
        final String expectedDescriptor;
        final String expectedToString;
        try (var scanResult = scan(/* reject = */ false, enableExternalClasses)) {
            final var twoIfaces = scanResult.getClassInfo(TwoIfaces.class.getName());
            expectedDescriptor = twoIfaces.getTypeDescriptor().toString();
            expectedToString = twoIfaces.toString();
        }
        assertThat(expectedDescriptor).contains("implements @" + TA.class.getName() + " " + GenIface.class.getName()
                + ", @" + TB.class.getName() + " " + Other.class.getName());
        try (var scanResult = scan(/* reject = */ true, enableExternalClasses)) {
            final var twoIfaces = scanResult.getClassInfo(TwoIfaces.class.getName());
            assertThat(twoIfaces.getTypeDescriptor().toString()).isEqualTo(expectedDescriptor);
            assertThat(twoIfaces.toString()).isEqualTo(expectedToString);
        }
    }
}
