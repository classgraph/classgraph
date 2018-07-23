package io.github.lukehutch.fastclasspathscanner.test.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalInterface;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalSuperclass;

public class InternalExternalTest {
    @Test
    public void testWhitelistingExternalClasses() {
        final ScanResult scanResult = new FastClasspathScanner().whitelistPackages(
                InternalExternalTest.class.getPackage().getName(), ExternalAnnotation.class.getName()).scan();
        assertThat(scanResult.getAllStandardClasses().getClassNames()).containsOnly(
                InternalExternalTest.class.getName(), InternalExtendsExternal.class.getName(),
                InternalImplementsExternal.class.getName(), InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testEnableExternalClasses() {
        final ScanResult scanResult = new FastClasspathScanner()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName(),
                        ExternalAnnotation.class.getName())
                .enableExternalClasses().scan();
        assertThat(scanResult.getAllStandardClasses().getClassNames()).containsOnly(
                ExternalSuperclass.class.getName(), InternalExternalTest.class.getName(),
                InternalExtendsExternal.class.getName(), InternalImplementsExternal.class.getName(),
                InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testWhitelistingExternalClassesWithoutEnablingExternalClasses() {
        final ScanResult scanResult = new FastClasspathScanner()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName(),
                        ExternalAnnotation.class.getName())
                .enableAllInfo().scan();
        assertThat(scanResult.getAllStandardClasses().getClassNames()).containsOnly(
                InternalExternalTest.class.getName(), InternalExtendsExternal.class.getName(),
                InternalImplementsExternal.class.getName(), InternalAnnotatedByExternal.class.getName());
        assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getClassNames())
                .containsOnly(InternalExtendsExternal.class.getName());
        assertThat(scanResult.getAllInterfaces()).isEmpty();
        assertThat(scanResult.getClassesImplementing(ExternalInterface.class.getName()).getClassNames())
                .containsOnly(InternalImplementsExternal.class.getName());
        assertThat(scanResult.getAllAnnotations().getClassNames()).isEmpty();
        assertThat(scanResult.getClassesWithAnnotation(ExternalAnnotation.class.getName()).getClassNames())
                .containsOnly(InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testIncludeReferencedClasses() {
        final ScanResult scanResult = new FastClasspathScanner()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName()).enableAllInfo().scan();
        assertThat(scanResult.getAllStandardClasses().getClassNames())
                .doesNotContain(ExternalSuperclass.class.getName());
        assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getClassNames())
                .containsOnly(InternalExtendsExternal.class.getName());
        assertThat(scanResult.getAllInterfaces().getClassNames()).doesNotContain(ExternalInterface.class.getName());
        assertThat(scanResult.getClassesImplementing(ExternalInterface.class.getName()).getClassNames())
                .containsOnly(InternalImplementsExternal.class.getName());
        assertThat(scanResult.getAllAnnotations().getClassNames())
                .doesNotContain(ExternalAnnotation.class.getName());
        assertThat(scanResult.getClassesWithAnnotation(ExternalAnnotation.class.getName()).getClassNames())
                .containsOnly(InternalAnnotatedByExternal.class.getName());
    }
}
