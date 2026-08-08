package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ObjectTypedValueWrapper#hashCode()} hashes every field, but
 * {@link ObjectTypedValueWrapper#equals(Object)} did not compare the
 * {@code boolean[]}, {@code char[]} and {@code double[]} fields, so wrappers
 * holding different arrays of those types compared equal.
 */
public class ObjectTypedValueWrapperEqualsTest {
    /** Wrappers holding different boolean, char or double arrays are not equal. */
    @Test
    public void arraysOfAllTypesAreCompared() {
        assertThat(new ObjectTypedValueWrapper(new boolean[] { true }))
                .isNotEqualTo(new ObjectTypedValueWrapper(new boolean[] { false }));
        assertThat(new ObjectTypedValueWrapper(new char[] { 'x' }))
                .isNotEqualTo(new ObjectTypedValueWrapper(new char[] { 'y' }));
        assertThat(new ObjectTypedValueWrapper(new double[] { 1.0 }))
                .isNotEqualTo(new ObjectTypedValueWrapper(new double[] { 2.0 }));

        assertThat(new ObjectTypedValueWrapper(new boolean[] { true }))
                .isEqualTo(new ObjectTypedValueWrapper(new boolean[] { true }));
        assertThat(new ObjectTypedValueWrapper(new char[] { 'x' }))
                .isEqualTo(new ObjectTypedValueWrapper(new char[] { 'x' }));
        assertThat(new ObjectTypedValueWrapper(new double[] { 1.0 }))
                .isEqualTo(new ObjectTypedValueWrapper(new double[] { 1.0 }));
    }
}
