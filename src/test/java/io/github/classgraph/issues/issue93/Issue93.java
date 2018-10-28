package io.github.classgraph.issues.issue93;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

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
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(PKG).enableAnnotationInfo()
                .ignoreClassVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(RetentionClass.class.getName()).getNames())
                    .containsExactlyInAnyOrder(RetentionClassAnnotated.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(RetentionRuntime.class.getName()).getNames())
                    .containsExactlyInAnyOrder(RetentionRuntimeAnnotated.class.getName());
        }
    }

    /**
     * Test that CLASS-retained annotations are not visible after calling
     * .setAnnotationVisibility(RetentionPolicy.RUNTIME), but RUNTIME-retained annotations are still visible.
     */
    @Test
    public void classRetentionIsNotVisibleWithRetentionPolicyRUNTIME() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(PKG).enableAnnotationInfo()
                .ignoreClassVisibility().disableRuntimeInvisibleAnnotations().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(RetentionClass.class.getName()).getNames()).isEmpty();
            assertThat(scanResult.getClassesWithAnnotation(RetentionRuntime.class.getName()).getNames())
                    .containsExactlyInAnyOrder(RetentionRuntimeAnnotated.class.getName());
        }
    }
}
