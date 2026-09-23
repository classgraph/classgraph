package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationParameterValue#equals(Object)} and {@link AnnotationParameterValue#hashCode()} compare array
 * values by their elements, for arrays of every element type.
 */
public class AnnotationParameterValueEqualsTest {
    /** Parameter values holding equal arrays are equal, whatever the array type. */
    @Test
    public void arraysOfAllTypesAreCompared() {
        assertThat(new AnnotationParameterValue("x", new boolean[] { true }))
                .isNotEqualTo(new AnnotationParameterValue("x", new boolean[] { false }));
        assertThat(new AnnotationParameterValue("x", new char[] { 'x' }))
                .isNotEqualTo(new AnnotationParameterValue("x", new char[] { 'y' }));
        assertThat(new AnnotationParameterValue("x", new double[] { 1.0 }))
                .isNotEqualTo(new AnnotationParameterValue("x", new double[] { 2.0 }));
        assertThat(new AnnotationParameterValue("x", new String[] { "a" }))
                .isNotEqualTo(new AnnotationParameterValue("x", new String[] { "b" }));

        assertThat(new AnnotationParameterValue("x", new boolean[] { true }))
                .isEqualTo(new AnnotationParameterValue("x", new boolean[] { true }));
        assertThat(new AnnotationParameterValue("x", new char[] { 'x' }))
                .isEqualTo(new AnnotationParameterValue("x", new char[] { 'x' }));
        assertThat(new AnnotationParameterValue("x", new double[] { 1.0 }))
                .isEqualTo(new AnnotationParameterValue("x", new double[] { 1.0 }));
        assertThat(new AnnotationParameterValue("x", new String[] { "a" }))
                .isEqualTo(new AnnotationParameterValue("x", new String[] { "a" }));
    }

    /** Equal parameter values have equal hash codes. */
    @Test
    public void equalValuesHaveEqualHashCodes() {
        assertThat(new AnnotationParameterValue("x", new double[] { 1.0 }))
                .hasSameHashCodeAs(new AnnotationParameterValue("x", new double[] { 1.0 }));
        assertThat(new AnnotationParameterValue("x", "a"))
                .hasSameHashCodeAs(new AnnotationParameterValue("x", "a"));
    }

    /**
     * An Object[] array that has not yet been converted equals the converted primitive array or String[] array
     * holding the same values, and has the same hash code, as {@link AnnotationParameterValue#compareTo} already
     * requires.
     */
    @Test
    public void anUnconvertedArrayEqualsTheConvertedArray() {
        final Object[][] pairs = { //
                { new Object[] { 1, 2 }, new int[] { 1, 2 } }, //
                { new Object[] { 1L, 2L }, new long[] { 1L, 2L } }, //
                { new Object[] { (short) 1 }, new short[] { 1 } }, //
                { new Object[] { 'x' }, new char[] { 'x' } }, //
                { new Object[] { true, false }, new boolean[] { true, false } }, //
                { new Object[] { (byte) -1 }, new byte[] { -1 } }, //
                { new Object[] { 1.5f, Float.NaN }, new float[] { 1.5f, Float.NaN } }, //
                { new Object[] { -0.0, Double.NaN }, new double[] { -0.0, Double.NaN } }, //
                { new Object[] { "a", "b" }, new String[] { "a", "b" } }, //
                // An empty array stays Object[] if the annotation class was not scanned
                { new Object[0], new int[0] } };
        for (final Object[] pair : pairs) {
            final var unconverted = new AnnotationParameterValue("x", pair[0]);
            final var converted = new AnnotationParameterValue("x", pair[1]);
            assertThat(unconverted.compareTo(converted)).isZero();
            assertThat(unconverted).isEqualTo(converted).hasSameHashCodeAs(converted);
            assertThat(converted).isEqualTo(unconverted);
        }
        // Values of different types are not equal, even if they are numerically equal
        final var ints = new AnnotationParameterValue("x", new Object[] { 1 });
        final var longs = new AnnotationParameterValue("x", new long[] { 1L });
        assertThat(ints).isNotEqualTo(longs);
        assertThat(ints.compareTo(longs)).isNotZero();
        assertThat(new AnnotationParameterValue("x", new Object[] { 1.0 }))
                .isNotEqualTo(new AnnotationParameterValue("x", new double[] { -1.0 }));
    }

    /** Parameter values with different names are not equal. */
    @Test
    public void nameIsCompared() {
        assertThat(new AnnotationParameterValue("x", "a")).isNotEqualTo(new AnnotationParameterValue("y", "a"));
    }
}
