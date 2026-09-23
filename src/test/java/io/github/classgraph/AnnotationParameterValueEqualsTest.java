package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationParameterValue#equals(Object)} and {@link AnnotationParameterValue#hashCode()} agree with
 * {@link AnnotationParameterValue#compareTo(AnnotationParameterValue)}.
 */
public class AnnotationParameterValueEqualsTest {
    /**
     * An Object[] array that has not yet been converted equals the converted primitive array or String[] array
     * holding the same values, and has the same hash code, as compareTo already requires.
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
            final AnnotationParameterValue unconverted = new AnnotationParameterValue("x", pair[0]);
            final AnnotationParameterValue converted = new AnnotationParameterValue("x", pair[1]);
            assertThat(unconverted.compareTo(converted)).isZero();
            assertThat(unconverted).isEqualTo(converted).hasSameHashCodeAs(converted);
            assertThat(converted).isEqualTo(unconverted);
        }
        // Values of different types are not equal, even if they are numerically equal
        final AnnotationParameterValue ints = new AnnotationParameterValue("x", new Object[] { 1 });
        final AnnotationParameterValue longs = new AnnotationParameterValue("x", new long[] { 1L });
        assertThat(ints).isNotEqualTo(longs);
        assertThat(ints.compareTo(longs)).isNotZero();
        assertThat(new AnnotationParameterValue("x", new Object[] { 1.0 }))
                .isNotEqualTo(new AnnotationParameterValue("x", new double[] { -1.0 }));
    }

    /** Equal scalar values have equal hash codes, and values with different names are not equal. */
    @Test
    public void scalarValuesAndNames() {
        assertThat(new AnnotationParameterValue("x", "a")).isEqualTo(new AnnotationParameterValue("x", "a"))
                .hasSameHashCodeAs(new AnnotationParameterValue("x", "a"));
        assertThat(new AnnotationParameterValue("x", "a")).isNotEqualTo(new AnnotationParameterValue("y", "a"));
        assertThat(new AnnotationParameterValue("x", 1)).isNotEqualTo(new AnnotationParameterValue("x", 1L));
    }
}
