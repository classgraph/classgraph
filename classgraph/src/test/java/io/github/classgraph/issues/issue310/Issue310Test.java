package io.github.classgraph.issues.issue310;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * The constant initializer value of a {@code static final double} field is read exactly, including for the
 * infinities and NaN.
 */
public class Issue310Test {
    /** The Constant A. */
    static final double A = 3.0;

    /** The Constant B. */
    static final double B = -4.0;

    /** The Constant C. */
    static final double C = Double.NEGATIVE_INFINITY;

    /** The Constant D. */
    static final double D = Double.POSITIVE_INFINITY;

    /** The Constant E. */
    static final double E = Double.NaN;

    /** Read the constant initializer value of each double constant. */
    @Test
    public void doubleConstantInitializerValues() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Issue310Test.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var classInfo = scanResult.getClassInfo(Issue310Test.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getFieldInfo("A").getConstantInitializerValue()).isEqualTo(3.0);
            assertThat(classInfo.getFieldInfo("B").getConstantInitializerValue()).isEqualTo(-4.0);
            assertThat(classInfo.getFieldInfo("C").getConstantInitializerValue())
                    .isEqualTo(Double.NEGATIVE_INFINITY);
            assertThat(classInfo.getFieldInfo("D").getConstantInitializerValue())
                    .isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(classInfo.getFieldInfo("E").getConstantInitializerValue()).isEqualTo(Double.NaN);
        }
    }
}
