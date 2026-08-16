package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.internal.module.ModuleReaderUtils;

/**
 * Scanning under Minecraft Forge aborted with {@code IllegalArgumentException: Could not call moduleReader.list()}.
 *
 * <p>
 * The cause is outside ClassGraph: Forge's {@code cpw.mods.cl.JarModuleFinder$JarModuleReader#list()} returns
 * {@code null}, which {@code java.lang.module.ModuleReader#list()} does not permit -- it is specified to return a
 * {@code Stream<String>}. Rather than aborting the scan, such a module is now treated as empty, and the log names
 * the module and the offending implementation, so that the report can go to the right project.
 */
public class Issue887Test {
    /** The name of the module defined by this test. */
    private static final String MODULE_NAME = "fake.module";

    /**
     * A {@link ModuleReader} that returns null from {@code list()}, as Forge's securejarhandler does.
     */
    static class NullListingModuleReader implements ModuleReader {
        /**
         * List the contents of the module.
         *
         * @return null -- which the {@link ModuleReader#list()} contract does not permit.
         */
        @Override
        public Stream<String> list() {
            return null;
        }

        @Override
        public Optional<URI> find(final String name) {
            return Optional.empty();
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * Define a module named {@value #MODULE_NAME}, whose {@link ModuleReader} violates the
     * {@link ModuleReader#list()} contract.
     *
     * @return the {@link ModuleReference} for the module.
     */
    private static ModuleReference fakeModule() {
        final var descriptor = ModuleDescriptor.newModule(MODULE_NAME).packages(Set.of("fake")).build();
        return new ModuleReference(descriptor, /* location = */ null) {
            @Override
            public ModuleReader open() {
                return new NullListingModuleReader();
            }
        };
    }

    /**
     * A {@link ModuleReader} that returns null from {@code list()} should be ignored silently, with the module
     * treated as empty -- but if verbose logging is enabled, the log should name the module and the offending
     * implementation class, and say whose contract was broken.
     *
     * @throws Exception
     *             if the module reader could not be opened.
     */
    @Test
    public void nullModuleReaderListingIsIgnoredButLogged() throws Exception {
        try (var moduleReader = ModuleReaderUtils.openModule(fakeModule())) {
            // Without logging, the module is silently treated as empty
            assertThat(ModuleReaderUtils.list(moduleReader, MODULE_NAME, /* log = */ null)).isEmpty();

            final var log = new LogNode();
            assertThat(ModuleReaderUtils.list(moduleReader, MODULE_NAME, log)).isEmpty();
            assertThat(log.toString()).contains("ModuleReader#list() returned null", MODULE_NAME,
                    NullListingModuleReader.class.getName());
        }
    }
}
