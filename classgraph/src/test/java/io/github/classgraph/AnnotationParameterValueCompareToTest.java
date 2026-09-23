package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationParameterValue#compareTo(AnnotationParameterValue)} used to order values by their string form,
 * so {@code 10} sorted before {@code 9}. Ordering values of the same type by their natural order, but values of
 * different types by their string form, would not be transitive: {@code 9 < 10} by natural order, but
 * {@code 10 < "5"} and {@code "5" < 9} by string form.
 */
public class AnnotationParameterValueCompareToTest {
    /**
     * Compare two parameter values with the same name.
     *
     * @param v0
     *            the first value.
     * @param v1
     *            the second value.
     * @return the result of the comparison.
     */
    private static int compare(final Object v0, final Object v1) {
        return new AnnotationParameterValue("x", v0).compareTo(new AnnotationParameterValue("x", v1));
    }

    /** Values of the same type are ordered by their natural order. */
    @Test
    public void valuesOfTheSameTypeAreOrderedByTheirNaturalOrder() {
        assertThat(compare(9, 10)).isNegative();
        assertThat(compare(10, 9)).isPositive();
        assertThat(compare(-1L, 2L)).isNegative();
        assertThat(compare(9.5, 10.0)).isNegative();
        assertThat(compare("b", "a")).isPositive();
        assertThat(compare(new AnnotationEnumValue("com.xyz.E", "A"), new AnnotationEnumValue("com.xyz.E", "B")))
                .isNegative();
    }

    /** Arrays are ordered element by element, by the natural order of the elements. */
    @Test
    public void arraysAreOrderedElementByElement() {
        assertThat(compare(new int[] { 1, 9 }, new int[] { 1, 10 })).isNegative();
        assertThat(compare(new int[] { 1 }, new int[] { 1, 0 })).isNegative();
        assertThat(compare(new double[] { 2.0 }, new double[] { 10.0 })).isNegative();
        // An Object[] array that has not yet been converted to a primitive array is equal to the converted array
        assertThat(compare(new Object[] { 1, 2 }, new int[] { 1, 2 })).isZero();
        assertThat(compare(new Object[] { 1, 9 }, new int[] { 1, 10 })).isNegative();
    }

    /** Values of different types are ordered by type, so that the order is transitive. */
    @Test
    public void theOrderIsTransitiveAcrossTypes() {
        final Object[] values = { 9, 10, "5" };
        for (final var a : values) {
            for (final var b : values) {
                for (final var c : values) {
                    if (compare(a, b) < 0 && compare(b, c) < 0) {
                        assertThat(compare(a, c)).as(a + " < " + b + " < " + c).isNegative();
                    }
                }
                assertThat(Integer.signum(compare(a, b))).isEqualTo(-Integer.signum(compare(b, a)));
            }
        }
    }
}
