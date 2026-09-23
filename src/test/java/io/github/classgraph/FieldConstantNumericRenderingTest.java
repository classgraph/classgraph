package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FieldInfo#toString()} rendered a numeric constant initializer value with {@link Object#toString()}, which
 * is not valid source for a float or long value outside the int range, or for a NaN or infinite value.
 */
public class FieldConstantNumericRenderingTest {
    /** Constants whose values need a type suffix or a named constant to be valid source. */
    public static class Constants {
        /** A float NaN. */
        public static final float F_NAN = Float.NaN;

        /** A float positive infinity. */
        public static final float F_POS_INF = Float.POSITIVE_INFINITY;

        /** A float negative infinity. */
        public static final float F_NEG_INF = Float.NEGATIVE_INFINITY;

        /** A finite float. */
        public static final float F_HALF = 1.5f;

        /** A double NaN. */
        public static final double D_NAN = Double.NaN;

        /** A double positive infinity. */
        public static final double D_POS_INF = Double.POSITIVE_INFINITY;

        /** A double negative infinity. */
        public static final double D_NEG_INF = Double.NEGATIVE_INFINITY;

        /** A finite double. */
        public static final double D_HALF = 2.5;

        /** A long outside the int range. */
        public static final long BIG = 10000000000L;

        /** An int. */
        public static final int INT = 7;
    }

    /** Numeric constant initializer values are rendered as valid Java source. */
    @Test
    public void numericConstantsAreRenderedAsValidSource() {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(Constants.class.getName()).enableFieldInfo()
                .enableStaticFinalFieldConstantInitializerValues().scan()) {
            final ClassInfo constants = scanResult.getClassInfo(Constants.class.getName());
            assertThat(constants.getFieldInfo("F_NAN").toString()).endsWith("F_NAN = Float.NaN");
            assertThat(constants.getFieldInfo("F_POS_INF").toString())
                    .endsWith("F_POS_INF = Float.POSITIVE_INFINITY");
            assertThat(constants.getFieldInfo("F_NEG_INF").toString())
                    .endsWith("F_NEG_INF = Float.NEGATIVE_INFINITY");
            assertThat(constants.getFieldInfo("F_HALF").toString()).endsWith("F_HALF = 1.5f");
            assertThat(constants.getFieldInfo("D_NAN").toString()).endsWith("D_NAN = Double.NaN");
            assertThat(constants.getFieldInfo("D_POS_INF").toString())
                    .endsWith("D_POS_INF = Double.POSITIVE_INFINITY");
            assertThat(constants.getFieldInfo("D_NEG_INF").toString())
                    .endsWith("D_NEG_INF = Double.NEGATIVE_INFINITY");
            assertThat(constants.getFieldInfo("D_HALF").toString()).endsWith("D_HALF = 2.5");
            assertThat(constants.getFieldInfo("BIG").toString()).endsWith("BIG = 10000000000L");
            assertThat(constants.getFieldInfo("INT").toString()).endsWith("INT = 7");
        }
    }
}
