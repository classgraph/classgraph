package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleReference;

import org.junit.jupiter.api.Test;

/**
 * {@link ModuleRef} wraps a {@link ModuleReference} together with the {@link ModuleLayer} it was resolved in, and
 * reads the module's descriptor, packages, location and classloader from it.
 */
public class ModuleRefTest {
    /**
     * Get the {@link ModuleReference} for a module in the boot layer.
     *
     * @param moduleName
     *            the module name.
     * @return the module reference.
     */
    private static ModuleReference bootLayerReference(final String moduleName) {
        final var resolvedModule = ModuleLayer.boot().configuration().findModule(moduleName);
        assertThat(resolvedModule).as("module " + moduleName + " in the boot layer").isPresent();
        return resolvedModule.get().reference();
    }

    /**
     * Wrap a module in the boot layer.
     *
     * @param moduleName
     *            the module name.
     * @return the {@link ModuleRef}.
     */
    private static ModuleRef bootLayerModule(final String moduleName) {
        return new ModuleRef(bootLayerReference(moduleName), ModuleLayer.boot());
    }

    /** A {@link ModuleRef} reports the name, descriptor, packages and version of the module it wraps. */
    @Test
    public void aModuleRefDescribesTheModuleItWraps() {
        final var reference = bootLayerReference("java.base");
        final var moduleRef = new ModuleRef(reference, ModuleLayer.boot());

        assertThat(moduleRef.getName()).isEqualTo("java.base");
        assertThat(moduleRef.getReference()).isSameAs(reference);
        assertThat(moduleRef.getLayer()).isSameAs(ModuleLayer.boot());
        assertThat(moduleRef.getDescriptor()).isSameAs(reference.descriptor());
        assertThat(moduleRef.getRawVersion()).isEqualTo(reference.descriptor().rawVersion().orElse(null));

        // The packages are sorted
        assertThat(moduleRef.getPackages()).contains("java.lang", "java.util").isSorted();

        // java.base is loaded by the bootstrap classloader, which is represented by null
        assertThat(moduleRef.getClassLoader()).isNull();

        assertThat(moduleRef).hasToString(reference.toString());
    }

    /** A system module has a {@code "jrt:"} location, which is not a file on disk. */
    @Test
    public void aSystemModuleHasAJrtLocation() {
        final var moduleRef = bootLayerModule("java.base");
        assertThat(moduleRef.isSystemModule()).isTrue();
        assertThat(moduleRef.getLocationURI()).hasToString("jrt:/java.base");
        // The location string is computed on the first call and cached thereafter
        assertThat(moduleRef.getLocationString()).isEqualTo("jrt:/java.base")
                .isEqualTo(moduleRef.getLocationString());
        assertThat(moduleRef.getLocationFile()).isNull();
    }

    /** Two {@link ModuleRef} objects are equal if they wrap the same module reference and layer. */
    @Test
    public void moduleRefsAreComparedByReferenceAndLayer() {
        final var moduleRef = bootLayerModule("java.base");
        final var sameModuleRef = bootLayerModule("java.base");
        assertThat(sameModuleRef).isNotSameAs(moduleRef).isEqualTo(moduleRef).hasSameHashCodeAs(moduleRef);
        assertThat(moduleRef.compareTo(sameModuleRef)).isZero();

        final var otherModuleRef = bootLayerModule("java.logging");
        assertThat(moduleRef).isEqualTo(moduleRef).isNotEqualTo(otherModuleRef).isNotEqualTo(null)
                .isNotEqualTo(moduleRef.toString());
        // Modules sort by name
        assertThat(moduleRef.compareTo(otherModuleRef)).isNegative();
        assertThat(otherModuleRef.compareTo(moduleRef)).isPositive();
    }

    /** Opening a {@link ModuleRef} gives a {@link java.lang.module.ModuleReader} for the module's content. */
    @Test
    public void openingAModuleGivesAReaderForItsContent() throws IOException {
        try (var moduleReader = bootLayerModule("java.base").open()) {
            final var resource = moduleReader.open("java/lang/Object.class");
            assertThat(resource).isPresent();
            try (var inputStream = resource.get()) {
                // A classfile starts with the 0xCAFEBABE magic number
                assertThat(inputStream.readNBytes(4)).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA,
                        (byte) 0xBE);
            }
        }
    }
}
