package io.github.classgraph.issues.issue93;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue93.
 */
public class Issue93 {
    /** The Constant PKG. */
    private static final String PKG = Issue93.class.getPackage().getName();

    /**
     * The Interface RetentionClass.
     */
    @Retention(RetentionPolicy.CLASS)
    private @interface RetentionClass {
    }

    /**
     * The Interface RetentionRuntime.
     */
    @Retention(RetentionPolicy.RUNTIME)
    private @interface RetentionRuntime {
    }

    /**
     * The Class RetentionClassAnnotated.
     */
    @RetentionClass
    static class RetentionClassAnnotated {
    }

    /**
     * The Class RetentionRuntimeAnnotated.
     */
    @RetentionRuntime
    static class RetentionRuntimeAnnotated {
    }

    /** Test that both CLASS-retained and RUNTIME-retained annotations are visible by default. */
    @Test
    public void classRetentionIsDefault() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(PKG).enableAnnotationInfo()
                .ignoreClassVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(RetentionClass.class.getName()).getNames())
                    .containsOnly(RetentionClassAnnotated.class.getName());
            assertThat(scanResult.getClassesWithAnnotation(RetentionRuntime.class.getName()).getNames())
                    .containsOnly(RetentionRuntimeAnnotated.class.getName());
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
                    .containsOnly(RetentionRuntimeAnnotated.class.getName());
        }
    }
}
