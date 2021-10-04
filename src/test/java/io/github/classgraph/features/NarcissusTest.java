package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * NarcissusTest.
 */
class NarcissusTest {
    /** Test Narcissus was able to start up OK. */
    @Test
    void annotationEquality() {
        ClassGraph.CIRCUMVENT_ENCAPSULATION = true;
        assertThat(ReflectionUtils.getStaticFieldVal(ReflectionUtils.class, "reflectionDriver", true).getClass()
                .getSimpleName()).isEqualTo("NarcissusReflectionDriver");
        try (ScanResult scanResult = new ClassGraph().acceptPackages(NarcissusTest.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isNotEmpty();
        } finally {
            ClassGraph.CIRCUMVENT_ENCAPSULATION = false;
        }
    }
}
