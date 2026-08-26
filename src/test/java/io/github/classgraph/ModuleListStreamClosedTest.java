package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;

/**
 * The stream returned by {@code ModuleReader#list()} is closed once it has been read. For an exploded module, that
 * stream walks a directory tree, and it is closing the stream that closes the directories it opened, so leaving it
 * unclosed leaks a file handle per module.
 *
 * <p>
 * (In package {@code io.github.classgraph} because {@link ModuleReaderProxy}'s constructor is package-private.)
 *
 * <p>
 * {@link ModuleRef} and {@link ModuleReaderProxy} call the JPMS types reflectively, so these fakes only need to be
 * duck-typed -- implementing {@code ModuleReference} and {@code ModuleReader} for real would require JDK 9+, and
 * the tests are compiled with {@code --release 8}.
 */
public class ModuleListStreamClosedTest {

    /** Set when the stream returned by {@link ClosedListingModuleReader#list()} is closed. */
    private static final AtomicBoolean streamClosed = new AtomicBoolean();

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

    /** A {@code ModuleReader} whose listing stream records whether it was closed. */
    public static class ClosedListingModuleReader implements AutoCloseable {
        /**
         * List the contents of the module.
         *
         * @return a stream that records that it was closed.
         */
        public Stream<String> list() {
            return Stream.of("fake/Resource.class").onClose(new Runnable() {
                @Override
                public void run() {
                    streamClosed.set(true);
                }
            });
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
         * @return a reader whose listing stream records whether it was closed.
         */
        public ClosedListingModuleReader open() {
            return new ClosedListingModuleReader();
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
            return ModuleListStreamClosedTest.class.getClassLoader();
        }
    }

    /**
     * Listing the contents of a module closes the stream the contents were listed through.
     *
     * @throws Exception
     *             if the module reader could not be opened.
     */
    @Test
    public void theStreamOfResourcePathsIsClosed() throws Exception {
        streamClosed.set(false);
        final ReflectionUtils reflectionUtils = new ReflectionUtils();
        final ModuleRef moduleRef = new ModuleRef(new FakeModuleReference(), new FakeModuleLayer(),
                reflectionUtils);
        try (ModuleReaderProxy moduleReaderProxy = new ModuleReaderProxy(moduleRef)) {
            assertThat(moduleReaderProxy.list()).containsExactly("fake/Resource.class");
        }
        assertThat(streamClosed).isTrue();
    }
}
