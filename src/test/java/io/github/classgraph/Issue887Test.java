package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Scanning under Minecraft Forge aborted with {@code IllegalArgumentException: Could not call moduleReader.list()}
 * (#887).
 *
 * <p>
 * The cause is outside ClassGraph: Forge's {@code cpw.mods.cl.JarModuleFinder$JarModuleReader#list()} returns
 * {@code null}, which {@code java.lang.module.ModuleReader#list()} does not permit -- it is specified to return a
 * {@code Stream<String>}. Rather than aborting the scan, such a module is now treated as empty, and the log names
 * the module and the offending implementation, so that the report can go to the right project.
 *
 * <p>
 * (In package {@code io.github.classgraph} because {@link ModuleReaderProxy}'s constructor is package-private.)
 */
public class Issue887Test {
    /** The name of the module defined by this test. */
    private static final String MODULE_NAME = "fake.module";

    /** A {@link ModuleReader} that returns null from {@code list()}, as Forge's securejarhandler does. */
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
     * Define a module layer containing a single module, {@value #MODULE_NAME}, whose {@link ModuleReader} violates
     * the {@link ModuleReader#list()} contract.
     *
     * @return the {@link ModuleRef} for the module.
     */
    private static ModuleRef fakeModuleRef() {
        final ModuleDescriptor descriptor = ModuleDescriptor.newModule(MODULE_NAME).packages(Set.of("fake")).build();
        final ModuleReference reference = new ModuleReference(descriptor, /* location = */ null) {
            @Override
            public ModuleReader open() {
                return new NullListingModuleReader();
            }
        };
        final ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(final String name) {
                return MODULE_NAME.equals(name) ? Optional.of(reference) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(reference);
            }
        };
        final ModuleLayer bootLayer = ModuleLayer.boot();
        final Configuration configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(),
                Set.of(MODULE_NAME));
        final ModuleLayer layer = bootLayer.defineModules(configuration,
                moduleName -> Issue887Test.class.getClassLoader());
        return new ModuleRef(reference, layer);
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
        try (ModuleReaderProxy moduleReaderProxy = new ModuleReaderProxy(fakeModuleRef())) {
            // Without logging, the module is silently treated as empty
            assertThat(moduleReaderProxy.list()).isEmpty();

            final LogNode log = new LogNode();
            assertThat(moduleReaderProxy.list(log)).isEmpty();
            assertThat(log.toString()).contains("ModuleReader#list() returned null", MODULE_NAME,
                    NullListingModuleReader.class.getName());
        }
    }
}
