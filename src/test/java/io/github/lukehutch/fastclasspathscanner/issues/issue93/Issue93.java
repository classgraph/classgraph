package io.github.lukehutch.fastclasspathscanner.issues.issue93;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Issue93 {
    private static final String PKG = Issue93.class.getPackage().getName();

    @Retention(RetentionPolicy.CLASS)
    private static @interface RetentionClass {
    }

    @Retention(RetentionPolicy.RUNTIME)
    private static @interface RetentionRuntime {
    }

    @RetentionClass
    static class RetentionClassAnnotated {
    }

    @RetentionRuntime
    static class RetentionRuntimeAnnotated {
    }

    /** Test that both CLASS-retained and RUNTIME-retained annotations are visible by default. */
    @Test
    public void classRetentionIsDefault() {
        final ScanResult scanResult = new FastClasspathScanner(PKG).scan();
        assertThat(scanResult.getNamesOfClassesWithAnnotation(RetentionClass.class))
                .containsOnly(RetentionClassAnnotated.class.getName());
        assertThat(scanResult.getNamesOfClassesWithAnnotation(RetentionRuntime.class))
                .containsOnly(RetentionRuntimeAnnotated.class.getName());
    }

    /**
     * Test that CLASS-retained annotations are not visible after calling
     * .setAnnotationVisibility(RetentionPolicy.RUNTIME), but RUNTIME-retained annotations are still visible.
     */
    @Test
    public void classRetentionIsNotVisibleWithRetentionPolicyRUNTIME() {
        final ScanResult scanResult = new FastClasspathScanner(PKG).setAnnotationVisibility(RetentionPolicy.RUNTIME)
                .scan();
        assertThat(scanResult.getNamesOfClassesWithAnnotation(RetentionClass.class)).isEmpty();
        assertThat(scanResult.getNamesOfClassesWithAnnotation(RetentionRuntime.class))
                .containsOnly(RetentionRuntimeAnnotated.class.getName());
    }
}
