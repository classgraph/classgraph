package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A resource in a module that cannot be read has to fail the same way every time it is read.
 *
 * <p>
 * (In package {@code io.github.classgraph} because {@link ClasspathElementModule} is package-private.)
 *
 * <p>
 * {@link ModuleRef} and {@link ModuleReaderProxy} call the JPMS types reflectively, so these fakes only need to be
 * duck-typed -- implementing {@code ModuleReference} and {@code ModuleReader} for real would require JDK 9+, and
 * the tests are compiled with {@code --release 8}.
 */
public class ModuleResourceReadFailureTest {

    /** The path of the resource that cannot be read. */
    private static final String UNREADABLE_PATH = "unreadable/resource.txt";

    /** Stands in for {@code ModuleDescriptor}. */
    public static class FakeDescriptor {
        /**
         * Get the module name.
         *
         * @return the module name.
         */
        public String name() {
            return "unreadable.module";
        }

        /**
         * Get the packages in the module.
         *
         * @return the packages in the module.
         */
        public Set<String> packages() {
            return Collections.singleton("unreadable");
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

    /** A {@code ModuleReader} that lists a resource which then cannot be read. */
    public static class FailingModuleReader implements AutoCloseable {
        /**
         * List the contents of the module.
         *
         * @return the path of the single resource in the module.
         */
        public Stream<String> list() {
            return Stream.of(UNREADABLE_PATH);
        }

        /**
         * Find a resource in the module.
         *
         * @param name
         *            the path of the resource.
         * @return the URI of the resource, if the module contains it.
         */
        public Optional<URI> find(final String name) {
            return name.equals(UNREADABLE_PATH) ? Optional.of(URI.create("module:/unreadable.module/" + name))
                    : Optional.<URI> empty();
        }

        /**
         * Open a resource as an {@link InputStream}.
         *
         * @param name
         *            the path of the resource.
         * @return never returns.
         * @throws IOException
         *             always.
         */
        public Optional<InputStream> open(final String name) throws IOException {
            throw new IOException("Simulated read failure");
        }

        /**
         * Read a resource into a {@link ByteBuffer}.
         *
         * @param name
         *            the path of the resource.
         * @return never returns.
         * @throws IOException
         *             always.
         */
        public Optional<ByteBuffer> read(final String name) throws IOException {
            throw new IOException("Simulated read failure");
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
         * @return a reader whose single resource cannot be read.
         */
        public FailingModuleReader open() {
            return new FailingModuleReader();
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
            return ModuleResourceReadFailureTest.class.getClassLoader();
        }
    }

    /**
     * A resource that cannot be read should throw {@link IOException} from every attempt to read it, rather than
     * failing the first time with the read error and every subsequent time with "Resource is already open". The
     * {@link ModuleReaderProxy} that was acquired for the failed read also has to be recycled, rather than being
     * left checked out of the recycler for the lifetime of the {@link ScanResult}.
     *
     * @throws Exception
     *             if the classpath element could not be opened.
     */
    @Test
    public void aResourceInAModuleThatCannotBeReadFailsTheSameWayEveryTime() throws Exception {
        final AtomicInteger moduleReadersOpened = new AtomicInteger();
        final ModuleRef moduleRef = new ModuleRef(new FakeModuleReference(), new FakeModuleLayer(),
                new ReflectionUtils());
        final SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, IOException> //
        unreadableModuleReaders = new SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, //
                IOException>() {
            @Override
            public Recycler<ModuleReaderProxy, IOException> newInstance(final ModuleRef key, final LogNode log) {
                return new Recycler<ModuleReaderProxy, IOException>() {
                    @Override
                    public ModuleReaderProxy newInstance() throws IOException {
                        moduleReadersOpened.incrementAndGet();
                        return new ModuleReaderProxy(key);
                    }
                };
            }
        };
        final ClasspathElementModule classpathElement = new ClasspathElementModule(moduleRef,
                unreadableModuleReaders,
                new ClasspathEntryWorkUnit(null, null, null, 0, "", new String[0]), new ScanSpec());
        classpathElement.open(/* workQueueIgnored = */ null, /* log = */ null);
        classpathElement.scanPaths(/* log = */ null);

        final Resource resource = classpathElement.getResource(UNREADABLE_PATH);
        assertThat(resource).as("resource with path " + UNREADABLE_PATH).isNotNull();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(resource::read).as("read").isInstanceOf(IOException.class)
                    .hasRootCauseMessage("Simulated read failure");
            assertThatThrownBy(resource::open).as("open").isInstanceOf(IOException.class)
                    .hasRootCauseMessage("Simulated read failure");
            assertThatThrownBy(resource::load).as("load").isInstanceOf(IOException.class)
                    .hasRootCauseMessage("Simulated read failure");
        }
        assertThat(moduleReadersOpened).as("module readers opened").hasValue(1);
    }
}
