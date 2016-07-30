package io.github.lukehutch.fastclasspathscanner.test.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalInterface;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalSuperclass;

public class InternalExternalTest {
    @Test
    public void testWhitelistingExternalClasses() {
        final ScanResult scanResult = new FastClasspathScanner(InternalExternalTest.class.getPackage().getName(),
                ExternalAnnotation.class.getName()).scan();
        assertThat(scanResult.getNamesOfAllStandardClasses()).containsOnly(ExternalSuperclass.class.getName(),
                InternalExternalTest.class.getName(), InternalExtendsExternal.class.getName(),
                InternalImplementsExternal.class.getName(), InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testWhitelistingExternalClassesWithStrictWhitelist() {
        final ScanResult scanResult = new FastClasspathScanner(InternalExternalTest.class.getPackage().getName(),
                ExternalAnnotation.class.getName()).strictWhitelist().scan();
        assertThat(scanResult.getNamesOfAllStandardClasses()).containsOnly(InternalExternalTest.class.getName(),
                InternalExtendsExternal.class.getName(), InternalImplementsExternal.class.getName(),
                InternalAnnotatedByExternal.class.getName());
        assertThat(scanResult.getNamesOfSubclassesOf(ExternalSuperclass.class.getName()))
                .containsExactly(InternalExtendsExternal.class.getName());
        assertThat(scanResult.getNamesOfAllInterfaceClasses()).isEmpty();
        assertThat(scanResult.getNamesOfClassesImplementing(ExternalInterface.class.getName()))
                .containsExactly(InternalImplementsExternal.class.getName());
        assertThat(scanResult.getNamesOfAllAnnotationClasses()).containsExactly(ExternalAnnotation.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(ExternalAnnotation.class.getName()))
                .containsExactly(InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testIncludeReferencedClasses() {
        final ScanResult scanResult = new FastClasspathScanner(InternalExternalTest.class.getPackage().getName())
                .strictWhitelist().scan();
        assertThat(scanResult.getNamesOfAllStandardClasses()).doesNotContain(ExternalSuperclass.class.getName());
        assertThat(scanResult.getNamesOfSubclassesOf(ExternalSuperclass.class.getName()))
                .containsExactly(InternalExtendsExternal.class.getName());
        assertThat(scanResult.getNamesOfAllInterfaceClasses()).doesNotContain(ExternalInterface.class.getName());
        assertThat(scanResult.getNamesOfClassesImplementing(ExternalInterface.class.getName()))
                .containsExactly(InternalImplementsExternal.class.getName());
        assertThat(scanResult.getNamesOfAllAnnotationClasses()).doesNotContain(ExternalAnnotation.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(ExternalAnnotation.class.getName()))
                .containsExactly(InternalAnnotatedByExternal.class.getName());
    }
}
