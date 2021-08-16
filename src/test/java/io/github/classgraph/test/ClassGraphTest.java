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
package io.github.classgraph.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList.ByteArrayConsumerThrowsIOException;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.accepted.Accepted;
import io.github.classgraph.test.accepted.AcceptedInterface;
import io.github.classgraph.test.accepted.Cls;
import io.github.classgraph.test.accepted.ClsSub;
import io.github.classgraph.test.accepted.ClsSubSub;
import io.github.classgraph.test.accepted.Iface;
import io.github.classgraph.test.accepted.IfaceSub;
import io.github.classgraph.test.accepted.IfaceSubSub;
import io.github.classgraph.test.accepted.Impl1;
import io.github.classgraph.test.accepted.Impl1Sub;
import io.github.classgraph.test.accepted.Impl1SubSub;
import io.github.classgraph.test.accepted.Impl2;
import io.github.classgraph.test.accepted.Impl2Sub;
import io.github.classgraph.test.accepted.Impl2SubSub;
import io.github.classgraph.test.accepted.StaticField;
import io.github.classgraph.test.accepted.rejectedsub.RejectedSub;
import io.github.classgraph.test.rejected.RejectedAnnotation;
import io.github.classgraph.test.rejected.RejectedSubclass;
import io.github.classgraph.test.rejected.RejectedSubinterface;
import io.github.classgraph.test.rejected.RejectedSuperclass;

/**
 * ClassGraphTest.
 */
public class ClassGraphTest {
    /** The Constant ROOT_PACKAGE. */
    private static final String ROOT_PACKAGE = ClassGraphTest.class.getPackage().getName();

    /** The Constant ACCEPT_PACKAGE. */
    private static final String ACCEPT_PACKAGE = Accepted.class.getPackage().getName();

    /**
     * Scan.
     */
    @Test
    public void scan() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).contains(ClassGraph.class.getName());
            assertThat(allClasses).contains(ClassGraphTest.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).contains(RejectedSub.class.getName());
        }
    }

    /**
     * Scan with accept.
     */
    @Test
    public void scanWithAccept() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraphTest.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).contains(RejectedSub.class.getName());
        }
    }

    /**
     * Scan with accept and reject.
     */
    @Test
    public void scanWithAcceptAndReject() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE)
                .rejectPackages(RejectedSub.class.getPackage().getName()).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraphTest.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).doesNotContain(RejectedSub.class.getName());
        }
    }

    /**
     * Scan sub and superclasses.
     */
    @Test
    public void scanSubAndSuperclasses() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            final List<String> subclasses = scanResult.getSubclasses(Cls.class).getNames();
            assertThat(subclasses).doesNotContain(Cls.class.getName());
            assertThat(subclasses).contains(ClsSub.class.getName());
            assertThat(subclasses).contains(ClsSubSub.class.getName());
            final List<String> superclasses = scanResult.getSuperclasses(ClsSubSub.class.getName()).getNames();
            assertThat(superclasses).doesNotContain(ClsSubSub.class.getName());
            assertThat(superclasses).contains(ClsSub.class.getName());
            assertThat(superclasses).contains(Cls.class.getName());
        }
    }

    /**
     * Scan sub and superinterface.
     */
    @Test
    public void scanSubAndSuperinterface() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            final List<String> subinterfaces = scanResult.getClassesImplementing(Iface.class).getNames();
            assertThat(subinterfaces).doesNotContain(Iface.class.getName());
            assertThat(subinterfaces).contains(IfaceSub.class.getName());
            assertThat(subinterfaces).contains(IfaceSubSub.class.getName());
            final List<String> superinterfaces = scanResult.getInterfaces(IfaceSubSub.class.getName()).getNames();
            assertThat(superinterfaces).doesNotContain(IfaceSubSub.class.getName());
            assertThat(superinterfaces).contains(IfaceSub.class.getName());
            assertThat(superinterfaces).contains(Iface.class.getName());
        }
    }

    /**
     * Scan transitive implements.
     */
    @Test
    public void scanTransitiveImplements() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            assertThat(scanResult.getClassesImplementing(Iface.class).getNames())
                    .doesNotContain(Iface.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .doesNotContain(Cls.class.getName());

            assertThat(scanResult.getClassesImplementing(Iface.class).getNames()).contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class).getNames())
                    .contains(Impl1SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .contains(Impl1SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .contains(Impl1SubSub.class.getName());

            assertThat(scanResult.getClassesImplementing(Iface.class).getNames()).contains(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .doesNotContain(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .doesNotContain(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class).getNames())
                    .contains(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .doesNotContain(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .doesNotContain(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class).getNames())
                    .contains(Impl2SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class).getNames())
                    .contains(Impl2SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class).getNames())
                    .contains(Impl2SubSub.class.getName());
        }
    }

    /**
     * Test external superclass returned.
     */
    @Test
    public void testExternalSuperclassReturned() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames())
                    .containsExactly(RejectedSuperclass.class.getName());
            assertThat(scanResult.getSubclasses(Accepted.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
        }
    }

    /**
     * Test accepted without exception without strict accept.
     */
    @Test
    public void testAcceptedWithoutExceptionWithoutStrictAccept() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames())
                    .containsExactly(RejectedSuperclass.class.getName());
        }
    }

    /**
     * Test can query with rejected annotation.
     */
    public void testCanQueryWithRejectedAnnotation() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(RejectedAnnotation.class).getNames())
                    .containsExactly(Accepted.class.getName());
        }
    }

    /**
     * Test rejected placeholder not returned.
     */
    @Test
    public void testRejectedPlaceholderNotReturned() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ROOT_PACKAGE)
                .rejectPackages(RejectedAnnotation.class.getPackage().getName()).enableAnnotationInfo().scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getSubclasses(Accepted.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
            assertThat(scanResult.getAnnotationsOnClass(AcceptedInterface.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test rejected package overrides accepted class with accepted override returned.
     */
    @Test
    public void testRejectedPackageOverridesAcceptedClassWithAcceptedOverrideReturned() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ROOT_PACKAGE)
                .rejectPackages(RejectedAnnotation.class.getPackage().getName())
                .acceptClasses(RejectedAnnotation.class.getName()).enableAnnotationInfo().scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Accepted.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test non accepted annotation returned without strict accept.
     */
    @Test
    public void testNonAcceptedAnnotationReturnedWithoutStrictAccept() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).enableAnnotationInfo()
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Accepted.class.getName()).getNames())
                    .containsOnly(RejectedAnnotation.class.getName());
        }
    }

    /**
     * Test external annotation returned.
     */
    @Test
    public void testExternalAnnotationReturned() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE).enableAnnotationInfo()
                .scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Accepted.class.getName()).getNames())
                    .containsExactly(RejectedAnnotation.class.getName());
        }
    }

    /**
     * Test rejected package.
     */
    public void testRejectedPackage() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(ROOT_PACKAGE, "-" + RejectedSuperclass.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getSubclasses(Accepted.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames()).isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(RejectedAnnotation.class).getNames()).isEmpty();
        }
    }

    /**
     * Test no exception if querying rejected.
     */
    public void testNoExceptionIfQueryingRejected() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(ACCEPT_PACKAGE, "-" + RejectedSuperclass.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSuperclasses(RejectedSuperclass.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test no exception if explicitly accepted class in rejected package.
     */
    public void testNoExceptionIfExplicitlyAcceptedClassInRejectedPackage() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(ACCEPT_PACKAGE,
                        "-" + RejectedSuperclass.class.getPackage().getName() + RejectedSuperclass.class.getName())
                .scan()) {
            assertThat(scanResult.getSuperclasses(RejectedSuperclass.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test visible if not rejected.
     */
    @Test
    public void testVisibleIfNotRejected() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ROOT_PACKAGE).enableAnnotationInfo().scan()) {
            assertThat(scanResult.getSuperclasses(Accepted.class.getName()).getNames())
                    .containsExactly(RejectedSuperclass.class.getName());
            assertThat(scanResult.getSubclasses(Accepted.class).getNames())
                    .containsExactly(RejectedSubclass.class.getName());
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames())
                    .containsExactly(RejectedSubinterface.class.getName());
            assertThat(scanResult.getClassesImplementing(AcceptedInterface.class).getNames())
                    .containsExactly(RejectedSubinterface.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(RejectedAnnotation.class).getNames())
                    .containsExactly(Accepted.class.getName());
        }
    }

    /**
     * Scan file pattern.
     */
    @Test
    public void scanFilePattern() {
        final AtomicBoolean readFileContents = new AtomicBoolean(false);
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").scan()) {
            try {
                scanResult.getResourcesWithLeafName("file-content-test.txt")
                        .forEachByteArrayThrowingIOException(new ByteArrayConsumerThrowsIOException() {
                            @Override
                            public void accept(final Resource resource, final byte[] byteArray) throws IOException {
                                readFileContents.set(new String(byteArray).equals("File contents"));
                            }
                        });
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
            assertThat(readFileContents.get()).isTrue();
        }
    }

    /**
     * Scan static final field name.
     */
    @Test
    public void scanStaticFinalFieldName() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE)
                .enableStaticFinalFieldConstantInitializerValues().scan()) {
            int numInitializers = 0;
            for (final FieldInfo fieldInfo : scanResult.getClassInfo(StaticField.class.getName()).getFieldInfo()) {
                if (fieldInfo.getConstantInitializerValue() != null) {
                    numInitializers++;
                }
            }
            assertThat(numInitializers).isEqualTo(0);
        }
    }

    /**
     * Scan static final field name ignore visibility.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void scanStaticFinalFieldNameIgnoreVisibility() throws Exception {
        final HashSet<String> fieldNames = new HashSet<>();
        for (final String fieldName : new String[] { "stringField", "intField", "boolField", "charField",
                "integerField", "booleanField" }) {
            fieldNames.add(StaticField.class.getName() + "." + fieldName);
        }
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ACCEPT_PACKAGE)
                .enableStaticFinalFieldConstantInitializerValues().ignoreFieldVisibility().scan()) {
            int numInitializers = 0;
            for (final FieldInfo fieldInfo : scanResult.getClassInfo(StaticField.class.getName()).getFieldInfo()) {
                final Object constInitializerValue = fieldInfo.getConstantInitializerValue();
                if (constInitializerValue != null) {
                    switch (fieldInfo.getName()) {
                    case "stringField":
                        assertThat("Static field contents").isEqualTo(constInitializerValue);
                        break;
                    case "intField":
                        assertThat(Integer.valueOf(3)).isEqualTo(constInitializerValue);
                        break;
                    case "boolField":
                        assertThat(Boolean.valueOf(true)).isEqualTo(constInitializerValue);
                        break;
                    case "charField":
                        assertThat(Character.valueOf('y')).isEqualTo(constInitializerValue);
                        break;
                    case "integerField":
                    case "booleanField":
                        throw new Exception("Non-constant field should not be matched");
                    default:
                        throw new Exception("Unknown field");
                    }
                    numInitializers++;
                }
            }
            assertThat(numInitializers).isEqualTo(4);
        }
    }

    /**
     * Generate graph viz file.
     */
    @Test
    public void generateGraphVizFile() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(ROOT_PACKAGE).enableAllInfo().scan()) {
            final String dotFile = scanResult.getAllClasses().generateGraphVizDotFile(20, 20);
            assertThat(dotFile).contains("\"" + ClsSub.class.getName() + "\" -> \"" + Cls.class.getName() + "\"");
        }
    }

    /**
     * Test get classpath elements.
     */
    @Test
    public void testGetClasspathElements() {
        assertThat(new ClassGraph().acceptPackages(ROOT_PACKAGE).enableAllInfo().getClasspathFiles().size())
                .isGreaterThan(0);
    }

    /**
     * Get the manifest.
     */
    @Test
    public void testGetManifest() {
        final AtomicBoolean foundManifest = new AtomicBoolean();
        try (ScanResult scanResult = new ClassGraph().acceptPaths("META-INF").enableAllInfo().scan()) {
            for (@SuppressWarnings("unused")
            final Resource res : scanResult.getResourcesWithLeafName("MANIFEST.MF")) {
                foundManifest.set(true);
            }
            assertThat(foundManifest.get()).isTrue();
        }
    }
}
