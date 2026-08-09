package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraph.CircumventEncapsulationMethod;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;

/**
 * Encapsulation circumvention test.
 */
class EncapsulationCircumventionTest {
    /** Reset encapsulation circumvention method after each test. */
    @AfterEach
    void resetAfterEachTest() {
        ClassGraph.CIRCUMVENT_ENCAPSULATION = CircumventEncapsulationMethod.NONE;
    }

    /** Test Narcissus. */
    @Test
    void testNarcissus() {
        ClassGraph.CIRCUMVENT_ENCAPSULATION = CircumventEncapsulationMethod.NARCISSUS;
        final var reflectionUtils = new ReflectionUtils();
        assertThat(
                reflectionUtils.getFieldVal(true, reflectionUtils, "reflectionDriver").getClass().getSimpleName())
                .isEqualTo("NarcissusReflectionDriver");
        try (var scanResult = new ClassGraph()
                .acceptPackages(EncapsulationCircumventionTest.class.getPackage().getName()).enableAllInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isNotEmpty();
        }
    }
}
