package io.github.classgraph.test.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalAnnotation;
import io.github.classgraph.test.external.ExternalInterface;
import io.github.classgraph.test.external.ExternalSuperclass;

public class InternalExternalTest {
    @Test
    public void testWhitelistingExternalClasses() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(
                InternalExternalTest.class.getPackage().getName(), ExternalAnnotation.class.getName()).scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames()).containsExactlyInAnyOrder(
                    InternalExternalTest.class.getName(), InternalExtendsExternal.class.getName(),
                    InternalImplementsExternal.class.getName(), InternalAnnotatedByExternal.class.getName());
        }
    }

    @Test
    public void testEnableExternalClasses() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName(),
                        ExternalAnnotation.class.getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames()).containsExactlyInAnyOrder(
                    ExternalSuperclass.class.getName(), InternalExternalTest.class.getName(),
                    InternalExtendsExternal.class.getName(), InternalImplementsExternal.class.getName(),
                    InternalAnnotatedByExternal.class.getName());
        }
    }

    @Test
    public void testWhitelistingExternalClassesWithoutEnablingExternalClasses() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName(),
                        ExternalAnnotation.class.getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames()).containsExactlyInAnyOrder(
                    InternalExternalTest.class.getName(), InternalExtendsExternal.class.getName(),
                    InternalImplementsExternal.class.getName(), InternalAnnotatedByExternal.class.getName());
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalExtendsExternal.class.getName());
            assertThat(scanResult.getAllInterfaces()).isEmpty();
            assertThat(scanResult.getClassesImplementing(ExternalInterface.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalImplementsExternal.class.getName());
            assertThat(scanResult.getAllAnnotations().getNames()).isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(ExternalAnnotation.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalAnnotatedByExternal.class.getName());
        }
    }

    @Test
    public void testIncludeReferencedClasses() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExternalTest.class.getPackage().getName()).enableAllInfo().scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames())
                    .doesNotContain(ExternalSuperclass.class.getName());
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalExtendsExternal.class.getName());
            assertThat(scanResult.getAllInterfaces().getNames()).doesNotContain(ExternalInterface.class.getName());
            assertThat(scanResult.getClassesImplementing(ExternalInterface.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalImplementsExternal.class.getName());
            assertThat(scanResult.getAllAnnotations().getNames())
                    .doesNotContain(ExternalAnnotation.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(ExternalAnnotation.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalAnnotatedByExternal.class.getName());
        }
    }
}
