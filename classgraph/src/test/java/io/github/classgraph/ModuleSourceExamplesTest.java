package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Executable examples distinguish module kinds from module-layer selection. */
public class ModuleSourceExamplesTest {
    /** Naming layers requires a module kind; enabling a kind does not add detected layers. */
    @Test
    public void namedLayersAndModuleKindsAreIndependent() {
        assertThatThrownBy(() -> new ClassGraph().enableModuleLayers(ModuleLayer.empty()).scan())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableSystemModules");
        try (var result = new ClassGraph().enableModuleLayers(ModuleLayer.empty()).enableSystemModules().scan()) {
            assertThat(result.getModuleReferences()).isEmpty();
        }
        try (var result = new ClassGraph().enableModuleLayers(ModuleLayer.empty()).enableSystemModules()
                .enableDetectedModuleLayers().acceptModules("java.base").scan()) {
            assertThat(result.getModuleReferences()).extracting(reference -> reference.descriptor().name())
                    .containsExactly("java.base");
        }
    }
}
