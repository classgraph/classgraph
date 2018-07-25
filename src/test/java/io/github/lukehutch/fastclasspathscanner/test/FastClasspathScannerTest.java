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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.FieldInfo;
import io.github.lukehutch.fastclasspathscanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedAnnotation;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedSubclass;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedSubinterface;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedSuperclass;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Cls;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.ClsSubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Iface;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.IfaceSubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1Sub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1SubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2Sub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl2SubSub;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.StaticField;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Whitelisted;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.WhitelistedInterface;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.blacklistedsub.BlacklistedSub;

public class FastClasspathScannerTest {
    private static final String ROOT_PACKAGE = FastClasspathScannerTest.class.getPackage().getName();
    private static final String WHITELIST_PACKAGE = Whitelisted.class.getPackage().getName();

    @Test
    public void scan() throws Exception {
        final List<String> allClasses = new FastClasspathScanner().enableClassInfo().scan().getAllClasses()
                .getNames();
        assertThat(allClasses).contains(Cls.class.getName());
        assertThat(allClasses).contains(FastClasspathScanner.class.getName());
        assertThat(allClasses).contains(FastClasspathScannerTest.class.getName());
        assertThat(allClasses).doesNotContain(String.class.getName());
        assertThat(allClasses).contains(BlacklistedSub.class.getName());
    }

    @Test
    public void scanWithWhitelist() throws Exception {
        final List<String> allClasses = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan()
                .getAllClasses().getNames();
        assertThat(allClasses).contains(Cls.class.getName());
        assertThat(allClasses).doesNotContain(FastClasspathScanner.class.getName());
        assertThat(allClasses).doesNotContain(FastClasspathScannerTest.class.getName());
        assertThat(allClasses).doesNotContain(String.class.getName());
        assertThat(allClasses).contains(BlacklistedSub.class.getName());
    }

    @Test
    public void lambda() throws Exception {
        final List<String> allClasses = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan()
                .getAllClasses().getNames();
        assertThat(allClasses).contains(BlacklistedSub.class.getName());
    }

    @Test
    public void scanWithWhitelistAndBlacklist() throws Exception {
        final List<String> allClasses = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .blacklistPackages(BlacklistedSub.class.getPackage().getName()).scan().getAllClasses().getNames();
        assertThat(allClasses).contains(Cls.class.getName());
        assertThat(allClasses).doesNotContain(FastClasspathScanner.class.getName());
        assertThat(allClasses).doesNotContain(FastClasspathScannerTest.class.getName());
        assertThat(allClasses).doesNotContain(String.class.getName());
        assertThat(allClasses).doesNotContain(BlacklistedSub.class.getName());
    }

    @Test
    public void scanSubAndSuperclasses() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan();
        final List<String> subclasses = scanResult.getSubclasses(Cls.class.getName()).getNames();
        assertThat(subclasses).doesNotContain(Cls.class.getName());
        assertThat(subclasses).contains(ClsSub.class.getName());
        assertThat(subclasses).contains(ClsSubSub.class.getName());
        final List<String> superclasses = scanResult.getSuperclasses(ClsSubSub.class.getName()).getNames();
        assertThat(superclasses).doesNotContain(ClsSubSub.class.getName());
        assertThat(superclasses).contains(ClsSub.class.getName());
        assertThat(superclasses).contains(Cls.class.getName());
    }

    @Test
    public void scanSubAndSuperinterface() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan();
        final List<String> subinterfaces = scanResult.getClassesImplementing(Iface.class.getName()).getNames();
        assertThat(subinterfaces).doesNotContain(Iface.class.getName());
        assertThat(subinterfaces).contains(IfaceSub.class.getName());
        assertThat(subinterfaces).contains(IfaceSubSub.class.getName());
        final List<String> superinterfaces = scanResult.getInterfaces(IfaceSubSub.class.getName()).getNames();
        assertThat(superinterfaces).doesNotContain(IfaceSubSub.class.getName());
        assertThat(superinterfaces).contains(IfaceSub.class.getName());
        assertThat(superinterfaces).contains(Iface.class.getName());
    }

    @Test
    public void scanTransitiveImplements() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan();
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

    @Test
    public void testWhitelistedWithoutExceptionWithStrictWhitelist() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan();
        assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
    }

    @Test
    public void testWhitelistedWithoutExceptionWithoutStrictWhitelist() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .enableExternalClasses().scan();
        assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames())
                .containsExactly(BlacklistedSuperclass.class.getName());
    }

    public void testCanQueryWithBlacklistedAnnotation() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE).scan();
        assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesWithAnnotation(BlacklistedAnnotation.class.getName()).getNames())
                .containsExactly(Whitelisted.class.getName());
    }

    @Test
    public void testBlacklistedPlaceholderNotReturned() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(ROOT_PACKAGE)
                .blacklistPackages(BlacklistedAnnotation.class.getPackage().getName()).enableAnnotationInfo()
                .scan();
        assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getAnnotationsOnClass(WhitelistedInterface.class.getName()).getNames()).isEmpty();
    }

    @Test
    public void testBlacklistedPackageOverridesWhitelistedClassWithWhitelistedOverrideReturned() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(ROOT_PACKAGE)
                .blacklistPackages(BlacklistedAnnotation.class.getPackage().getName())
                .whitelistClasses(BlacklistedAnnotation.class.getName()).enableAnnotationInfo().scan();
        assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames()).isEmpty();
    }

    @Test
    public void testNonWhitelistedAnnotationReturnedWithoutStrictWhitelist() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .enableAnnotationInfo().enableExternalClasses().scan();
        assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames())
                .containsOnly(BlacklistedAnnotation.class.getName());
    }

    @Test
    public void testNonWhitelistedAnnotationNotReturnedWithStrictWhitelist() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .enableAnnotationInfo().scan();
        assertThat(scanResult.getAnnotationsOnClass(Whitelisted.class.getName()).getNames()).isEmpty();
    }

    public void testBlacklistedPackage() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner()
                .whitelistPackages(ROOT_PACKAGE, "-" + BlacklistedSuperclass.class.getPackage().getName()).scan();
        assertThat(scanResult.getSuperclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getSubclasses(Whitelisted.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesImplementing(WhitelistedInterface.class.getName()).getNames()).isEmpty();
        assertThat(scanResult.getClassesWithAnnotation(BlacklistedAnnotation.class.getName()).getNames());
    }

    public void testNoExceptionIfQueryingBlacklisted() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner()
                .whitelistPackages(WHITELIST_PACKAGE, "-" + BlacklistedSuperclass.class.getPackage().getName())
                .scan();
        assertThat(scanResult.getSuperclasses(BlacklistedSuperclass.class.getName()).getNames()).isEmpty();
    }

    public void testNoExceptionIfExplicitlyWhitelistedClassInBlacklistedPackage() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE,
                "-" + BlacklistedSuperclass.class.getPackage().getName() + BlacklistedSuperclass.class.getName())
                .scan();
        assertThat(scanResult.getSuperclasses(BlacklistedSuperclass.class.getName()).getNames()).isEmpty();
    }

    @Test
    public void testVisibleIfNotBlacklisted() throws Exception {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(ROOT_PACKAGE)
                .enableAnnotationInfo().scan();
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

    @Test
    public void scanFilePattern() throws Exception {
        final AtomicBoolean readFileContents = new AtomicBoolean(false);
        new FastClasspathScanner().whitelistPaths("").scan().getResourcesWithLeafName("file-content-test.txt")
                .forEachByteArray((res, arr) -> readFileContents.set(new String(arr).equals("File contents")));
        assertThat(readFileContents.get()).isTrue();
    }

    @Test
    public void scanStaticFinalFieldName() throws Exception {
        int numInitializers = 0;
        for (final FieldInfo fieldInfo : new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .enableStaticFinalFieldConstantInitializerValues().scan().getClassInfo(StaticField.class.getName())
                .getFieldInfo()) {
            if (fieldInfo.getConstantInitializerValue() != null) {
                numInitializers++;
            }
        }
        assertThat(numInitializers).isEqualTo(0);
    }

    @Test
    public void scanStaticFinalFieldNameIgnoreVisibility() throws Exception {
        final HashSet<String> fieldNames = new HashSet<>();
        for (final String fieldName : new String[] { "stringField", "intField", "boolField", "charField",
                "integerField", "booleanField" }) {
            fieldNames.add(StaticField.class.getName() + "." + fieldName);
        }
        int numInitializers = 0;
        for (final FieldInfo fieldInfo : new FastClasspathScanner().whitelistPackages(WHITELIST_PACKAGE)
                .enableStaticFinalFieldConstantInitializerValues().ignoreFieldVisibility().scan()
                .getClassInfo(StaticField.class.getName()).getFieldInfo()) {
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
                    throw new RuntimeException("Non-constant field should not be matched");
                default:
                    throw new RuntimeException("Unknown field");
                }
                numInitializers++;
            }
        }
        assertThat(numInitializers).isEqualTo(4);
    }

    @Test
    public void generateGraphVizFile() {
        final String dotFile = new FastClasspathScanner().whitelistPackages(ROOT_PACKAGE).enableAllInfo().scan()
                .getAllClasses().generateGraphVizDotFile(20, 20);
        assertThat(dotFile).contains("\"" + ClsSub.class.getName() + "\" -> \"" + Cls.class.getName() + "\"");
    }

    @Test
    public void testGetClasspathElements() {
        assertThat(new FastClasspathScanner().whitelistPackages(ROOT_PACKAGE).enableAllInfo().getClasspathFiles()
                .size()).isGreaterThan(0);
    }

    @Test
    public void getManifest() throws Exception {
        final AtomicBoolean foundManifest = new AtomicBoolean();
        new FastClasspathScanner().whitelistPaths("META-INF").enableAllInfo().scan()
                .getResourcesWithLeafName("MANIFEST.MF").forEach(r -> foundManifest.set(true));
        assertThat(foundManifest.get()).isTrue();
    }
}
