package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
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
 *
 * <p>
 * {@link ModuleRef} and {@link ModuleReaderProxy} call the JPMS types reflectively, so these fakes only need to be
 * duck-typed -- implementing {@code ModuleReference} and {@code ModuleReader} for real would require JDK 9+, and
 * the tests are compiled with {@code --release 8}.
 */
public class Issue887Test {

    /** Stands in for {@code ModuleDescriptor}. */
    public static class FakeDescriptor {
        /**
         * Get the module name.
         *
         * @return the module name.
         */
        public String name() {
            return "fake.module";
        }

        /**
         * Get the packages in the module.
         *
         * @return the packages in the module.
         */
        public Set<String> packages() {
            return Collections.singleton("fake");
        }

        /**
         * Get the raw version.
         *
         * @return the raw version, if any.
         */
        public Optional<String> rawVersion() {
            return Optional.empty();
        }
    }

    /** A {@code ModuleReader} that returns null from {@code list()}, as Forge's securejarhandler does. */
    public static class NullListingModuleReader implements AutoCloseable {
        /**
         * List the contents of the module.
         *
         * @return null -- which the {@code ModuleReader#list()} contract does not permit.
         */
        public Stream<String> list() {
            return null;
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /** Stands in for {@code ModuleReference}. */
    public static class FakeModuleReference {
        /**
         * Get the module descriptor.
         *
         * @return the module descriptor.
         */
        public FakeDescriptor descriptor() {
            return new FakeDescriptor();
        }

        /**
         * Get the module location.
         *
         * @return the module location, if any.
         */
        public Optional<URI> location() {
            return Optional.empty();
        }

        /**
         * Open a reader for the module.
         *
         * @return a reader that violates the {@code ModuleReader#list()} contract.
         */
        public NullListingModuleReader open() {
            return new NullListingModuleReader();
        }
    }

    /** Stands in for {@code ModuleLayer}. */
    public static class FakeModuleLayer {
        /**
         * Find the classloader for a module.
         *
         * @param moduleName
         *            the module name.
         * @return the classloader for the module.
         */
        public ClassLoader findLoader(final String moduleName) {
            return Issue887Test.class.getClassLoader();
        }
    }

    /**
     * A {@code ModuleReader} that returns null from {@code list()} should be ignored silently, with the module
     * treated as empty -- but if verbose logging is enabled, the log should name the module and the offending
     * implementation class, and say whose contract was broken.
     *
     * @throws Exception
     *             if the module reader could not be opened.
     */
    @Test
    public void nullModuleReaderListingIsIgnoredButLogged() throws Exception {
        final ReflectionUtils reflectionUtils = new ReflectionUtils();
        final ModuleRef moduleRef = new ModuleRef(new FakeModuleReference(), new FakeModuleLayer(),
                reflectionUtils);
        try (ModuleReaderProxy moduleReaderProxy = new ModuleReaderProxy(moduleRef)) {
            // Without logging, the module is silently treated as empty
            assertThat(moduleReaderProxy.list()).isEmpty();

            final LogNode log = new LogNode();
            assertThat(moduleReaderProxy.list(log)).isEmpty();
            assertThat(log.toString()).contains("ModuleReader#list() returned null", "fake.module",
                    NullListingModuleReader.class.getName());
        }
    }
}
