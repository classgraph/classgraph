package io.github.lukehutch.fastclasspathscanner.test.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalInterface;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalSuperclass;

public class InternalExternalTest {
    @Test
    public void testWhitelistingExternalClasses() {
        final FastClasspathScanner scanner = new FastClasspathScanner(
                InternalExternalTest.class.getPackage().getName(), ExternalAnnotation.class.getName()).scan();
        assertThat(scanner.getNamesOfAllStandardClasses()).containsOnly(InternalExternalTest.class.getName(),
                InternalExtendsExternal.class.getName(), InternalImplementsExternal.class.getName(),
                InternalAnnotatedByExternal.class.getName());
        assertThat(scanner.getNamesOfSubclassesOf(ExternalSuperclass.class.getName()))
                .containsExactly(InternalExtendsExternal.class.getName());
        assertThat(scanner.getNamesOfAllInterfaceClasses()).isEmpty();
        assertThat(scanner.getNamesOfClassesImplementing(ExternalInterface.class.getName()))
                .containsExactly(InternalImplementsExternal.class.getName());
        assertThat(scanner.getNamesOfAllAnnotationClasses()).containsExactly(ExternalAnnotation.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(ExternalAnnotation.class.getName()))
                .containsExactly(InternalAnnotatedByExternal.class.getName());
    }

    @Test
    public void testIncludeReferencedClasses() {
        final FastClasspathScanner scanner = new FastClasspathScanner(
                InternalExternalTest.class.getPackage().getName()).scan();
        assertThat(scanner.getNamesOfAllStandardClasses()).doesNotContain(ExternalSuperclass.class.getName());
        assertThat(scanner.getNamesOfSubclassesOf(ExternalSuperclass.class.getName()))
                .containsExactly(InternalExtendsExternal.class.getName());
        assertThat(scanner.getNamesOfAllInterfaceClasses()).doesNotContain(ExternalInterface.class.getName());
        assertThat(scanner.getNamesOfClassesImplementing(ExternalInterface.class.getName()))
                .containsExactly(InternalImplementsExternal.class.getName());
        assertThat(scanner.getNamesOfAllAnnotationClasses()).doesNotContain(ExternalAnnotation.class.getName());
        assertThat(scanner.getNamesOfClassesWithAnnotation(ExternalAnnotation.class.getName()))
                .containsExactly(InternalAnnotatedByExternal.class.getName());
    }
}
