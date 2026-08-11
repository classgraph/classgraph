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

    /** Parameter values with different names are not equal. */
    @Test
    public void nameIsCompared() {
        assertThat(new AnnotationParameterValue("x", "a")).isNotEqualTo(new AnnotationParameterValue("y", "a"));
    }
}
