package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * A setting changes the one thing it names, and pulls in another setting only when it would otherwise do nothing.
 */
public class SettingsAreIndependentTest {
    /**
     * The module layers the caller names replace the ones that are visible from the caller, so asking for the
     * system modules of the named layers does not scan the boot layer as well.
     */
    @Test
    public void namedModuleLayersReplaceTheDetectedOnes() {
        // An empty ModuleLayer has no modules and no parents, so any class found in the scan below came from a
        // module layer that the caller did not name
        try (var scanResult = new ClassGraph().enableModuleLayers(ModuleLayer.empty()).enableSystemModules()
                .enableClassInfo().acceptPackages("java.lang").scan()) {
            assertThat(scanResult.getAllClasses()).isEmpty();
        }

        // Without the named layer, the same scan reaches the boot layer, which is what makes the assertion above
        // meaningful
        try (var scanResult = new ClassGraph().enableSystemModules().enableClassInfo().acceptPackages("java.lang")
                .scan()) {
            assertThat(scanResult.getAllClasses()).isNotEmpty();
        }
    }

    /**
     * Adding the JRE's own jars to the scan does not turn on the reading of class information: they can be scanned
     * for resources only.
     */
    @Test
    public void enablingSystemJarsDoesNotEnableClassInfo() {
        try (var scanResult = new ClassGraph().enableSystemJars().scan()) {
            assertThatThrownBy(scanResult::getAllClasses).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Ignoring the runtime invisible annotations narrows what is scanned, so it does not turn on the reading of
     * class information, and does not turn on the reading of annotations either.
     */
    @Test
    public void ignoringRuntimeInvisibleAnnotationsEnablesNothing() {
        try (var scanResult = new ClassGraph().disableRuntimeInvisibleAnnotations().scan()) {
            assertThatThrownBy(scanResult::getAllClasses).isInstanceOf(IllegalStateException.class);
        }
    }
}
