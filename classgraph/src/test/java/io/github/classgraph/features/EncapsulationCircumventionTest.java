package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;

/**
 * Encapsulation circumvention test. Narcissus is an optional dependency of this project, so it is on the test
 * classpath, and ClassGraph should pick it up as its reflection driver without being asked to.
 */
class EncapsulationCircumventionTest {
    /**
     * Narcissus is used as the reflection driver when it is on the classpath.
     *
     * @throws ReflectiveOperationException
     *             if the reflection driver field could not be read.
     */
    @Test
    void narcissusIsUsedWhenItIsAvailable() throws ReflectiveOperationException {
        // The tests run on the classpath, so plain reflection can read the private field
        final var driverField = ReflectionUtils.class.getDeclaredField("REFLECTION_DRIVER");
        driverField.setAccessible(true);
        assertThat(driverField.get(null).getClass().getSimpleName()).isEqualTo("NarcissusReflectionDriver");
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(EncapsulationCircumventionTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isNotEmpty();
        }
    }
}
