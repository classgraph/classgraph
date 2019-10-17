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

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList.ByteArrayConsumer;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.blacklisted.BlacklistedAnnotation;
import io.github.classgraph.test.blacklisted.BlacklistedSubclass;
import io.github.classgraph.test.blacklisted.BlacklistedSubinterface;
import io.github.classgraph.test.blacklisted.BlacklistedSuperclass;
import io.github.classgraph.test.whitelisted.Cls;
import io.github.classgraph.test.whitelisted.ClsSub;
import io.github.classgraph.test.whitelisted.ClsSubSub;
import io.github.classgraph.test.whitelisted.Iface;
import io.github.classgraph.test.whitelisted.IfaceSub;
import io.github.classgraph.test.whitelisted.IfaceSubSub;
import io.github.classgraph.test.whitelisted.Impl1;
import io.github.classgraph.test.whitelisted.Impl1Sub;
import io.github.classgraph.test.whitelisted.Impl1SubSub;
import io.github.classgraph.test.whitelisted.Impl2;
import io.github.classgraph.test.whitelisted.Impl2Sub;
import io.github.classgraph.test.whitelisted.Impl2SubSub;
import io.github.classgraph.test.whitelisted.StaticField;
import io.github.classgraph.test.whitelisted.Whitelisted;
import io.github.classgraph.test.whitelisted.WhitelistedInterface;
import io.github.classgraph.test.whitelisted.blacklistedsub.BlacklistedSub;

/**
 * ClassGraphTest.
 */
public class ClassGraphTest {
    /** The Constant ROOT_PACKAGE. */
    private static final String ROOT_PACKAGE = ClassGraphTest.class.getPackage().getName();

    /** The Constant WHITELIST_PACKAGE. */
    private static final String WHITELIST_PACKAGE = Whitelisted.class.getPackage().getName();

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
            assertThat(allClasses).contains(BlacklistedSub.class.getName());
        }
    }

    /**
     * Scan with whitelist.
     */
    @Test
    public void scanWithWhitelist() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraphTest.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).contains(BlacklistedSub.class.getName());
        }
    }

    /**
     * Scan with whitelist and blacklist.
     */
    @Test
    public void scanWithWhitelistAndBlacklist() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE)
                .blacklistPackages(BlacklistedSub.class.getPackage().getName()).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraphTest.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).doesNotContain(BlacklistedSub.class.getName());
        }
    }

    /**
     * Scan sub and superclasses.
     */
    @Test
    public void scanSubAndSuperclasses() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            final List<String> subclasses = scanResult.getSubclasses(Cls.class.getName()).getNames();
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
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            final List<String> subinterfaces = scanResult.getClassesImplementing(Iface.class.getName()).getNames();
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
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .doesNotContain(Iface.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .doesNotContain(Cls.class.getName());

            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .contains(Impl1.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .contains(Impl1Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl1SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .contains(Impl1SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .contains(Impl1SubSub.class.getName());

            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .doesNotContain(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .doesNotContain(Impl2.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .doesNotContain(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .doesNotContain(Impl2Sub.class.getName());
            assertThat(scanResult.getClassesImplementing(Iface.class.getName()).getNames())
                    .contains(Impl2SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSub.class.getName()).getNames())
                    .contains(Impl2SubSub.class.getName());
            assertThat(scanResult.getClassesImplementing(IfaceSubSub.class.getName()).getNames())
                    .contains(Impl2SubSub.class.getName());
        }
    }

    /**
     * Test external superclass returned.
     */
    @Test
    public void testExternalSuperclassReturned() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames())
                    .containsExactly(BlacklistedSuperclass.class.getName());
            assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
        }
    }

    /**
     * Test whitelisted without exception without strict whitelist.
     */
    @Test
    public void testWhitelistedWithoutExceptionWithoutStrictWhitelist() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames())
                    .containsExactly(BlacklistedSuperclass.class.getName());
        }
    }

    /**
     * Test can query with blacklisted annotation.
     */
    public void testCanQueryWithBlacklistedAnnotation() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(BlacklistedAnnotation.class.getName()).getNames())
                    .containsExactly(Whitelisted.class.getName());
        }
    }

    /**
     * Test blacklisted placeholder not returned.
     */
    @Test
    public void testBlacklistedPlaceholderNotReturned() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(ROOT_PACKAGE)
                .blacklistPackages(BlacklistedAnnotation.class.getPackage().getName()).enableAnnotationInfo()
                .scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
            assertThat(scanResult.getAnnotationsOnClass(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test blacklisted package overrides whitelisted class with whitelisted override returned.
     */
    @Test
    public void testBlacklistedPackageOverridesWhitelistedClassWithWhitelistedOverrideReturned() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(ROOT_PACKAGE)
                .blacklistPackages(BlacklistedAnnotation.class.getPackage().getName())
                .whitelistClasses(BlacklistedAnnotation.class.getName()).enableAnnotationInfo().scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test non whitelisted annotation returned without strict whitelist.
     */
    @Test
    public void testNonWhitelistedAnnotationReturnedWithoutStrictWhitelist() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).enableAnnotationInfo()
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames())
                    .containsOnly(BlacklistedAnnotation.class.getName());
        }
    }

    /**
     * Test external annotation returned.
     */
    @Test
    public void testExternalAnnotationReturned() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).enableAnnotationInfo()
                .scan()) {
            assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames())
                    .containsExactly(BlacklistedAnnotation.class.getName());
        }
    }

    /**
     * Test blacklisted package.
     */
    public void testBlacklistedPackage() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(ROOT_PACKAGE, "-" + BlacklistedSuperclass.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(BlacklistedAnnotation.class.getName()).getNames())
                    .isEmpty();
        }
    }

    /**
     * Test no exception if querying blacklisted.
     */
    public void testNoExceptionIfQueryingBlacklisted() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(WHITELIST_PACKAGE, "-" + BlacklistedSuperclass.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getSuperclasses(BlacklistedSuperclass.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test no exception if explicitly whitelisted class in blacklisted package.
     */
    public void testNoExceptionIfExplicitlyWhitelistedClassInBlacklistedPackage() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE,
                "-" + BlacklistedSuperclass.class.getPackage().getName() + BlacklistedSuperclass.class.getName())
                .scan()) {
            assertThat(scanResult.getSuperclasses(BlacklistedSuperclass.class.getName()).getNames()).isEmpty();
        }
    }

    /**
     * Test visible if not blacklisted.
     */
    @Test
    public void testVisibleIfNotBlacklisted() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(ROOT_PACKAGE).enableAnnotationInfo()
                .scan()) {
            assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames())
                    .containsExactly(BlacklistedSuperclass.class.getName());
            assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames())
                    .containsExactly(BlacklistedSubclass.class.getName());
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .containsExactly(BlacklistedSubinterface.class.getName());
            assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames())
                    .containsExactly(BlacklistedSubinterface.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(BlacklistedAnnotation.class.getName()).getNames())
                    .containsExactly(Whitelisted.class.getName());
        }
    }

    /**
     * Scan file pattern.
     */
    @Test
    public void scanFilePattern() {
        final AtomicBoolean readFileContents = new AtomicBoolean(false);
        try (ScanResult scanResult = new ClassGraph().whitelistPathsNonRecursive("").scan()) {
            scanResult.getResourcesWithLeafName("file-content-test.txt").forEachByteArray(new ByteArrayConsumer() {
                @Override
                public void accept(final Resource res, final byte[] arr) {
                    readFileContents.set(new String(arr).equals("File contents"));
                }
            });
            assertThat(readFileContents.get()).isTrue();
        }
    }

    /**
     * Scan static final field name.
     */
    @Test
    public void scanStaticFinalFieldName() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE)
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
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE)
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
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(ROOT_PACKAGE).enableAllInfo().scan()) {
            final String dotFile = scanResult.getAllClasses().generateGraphVizDotFile(20, 20);
            assertThat(dotFile).contains("\"" + ClsSub.class.getName() + "\" -> \"" + Cls.class.getName() + "\"");
        }
    }

    /**
     * Test get classpath elements.
     */
    @Test
    public void testGetClasspathElements() {
        assertThat(new ClassGraph().whitelistPackages(ROOT_PACKAGE).enableAllInfo().getClasspathFiles().size())
                .isGreaterThan(0);
    }

    /**
     * Get the manifest.
     */
    @Test
    public void testGetManifest() {
        final AtomicBoolean foundManifest = new AtomicBoolean();
        try (ScanResult scanResult = new ClassGraph().whitelistPaths("META-INF").enableAllInfo().scan()) {
            for (@SuppressWarnings("unused")
            final Resource res : scanResult.getResourcesWithLeafName("MANIFEST.MF")) {
                foundManifest.set(true);
            }
            assertThat(foundManifest.get()).isTrue();
        }
    }
}
