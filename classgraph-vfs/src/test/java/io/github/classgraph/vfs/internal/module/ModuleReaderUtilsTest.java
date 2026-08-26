package io.github.classgraph.vfs.internal.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import io.github.classgraph.base.LogNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for the calls into the module system. Some {@link ModuleReader} implementations in the wild break the
 * {@code ModuleReader} contract, so these check what happens when one of them does.
 */
public class ModuleReaderUtilsTest {
    /** The classfile magic number, which starts every classfile. */
    private static final int CLASSFILE_MAGIC = 0xCAFEBABE;

    /** The ways a {@link ModuleReader} can fail to return what its contract says it returns. */
    enum Failure {
        /** Return null, which the contract does not permit. */
        NULL,

        /** Return an empty {@link Optional}, meaning the module does not contain the resource. */
        EMPTY,

        /** Throw an {@link IOException}. */
        IO_EXCEPTION
    }

    /** A {@link ModuleReader} that fails in the given way whichever of its methods is called. */
    private record FailingModuleReader(Failure failure) implements ModuleReader {
        /**
         * Fail in the configured way.
         *
         * @param <T>
         *            the type of the value that was asked for
         * @return null, or an empty {@link Optional}
         * @throws IOException
         *             if the failure is {@link Failure#IO_EXCEPTION}
         */
        private <T> @Nullable Optional<T> fail() throws IOException {
            if (failure == Failure.IO_EXCEPTION) {
                throw new IOException("Simulated failure");
            }
            return failure == Failure.NULL ? null : Optional.empty();
        }

        @Override
        public Optional<URI> find(final String name) throws IOException {
            return fail();
        }

        @Override
        public Optional<InputStream> open(final String name) throws IOException {
            return fail();
        }

        @Override
        public Optional<ByteBuffer> read(final String name) throws IOException {
            return fail();
        }

        @Override
        public Stream<String> list() throws IOException {
            if (failure == Failure.IO_EXCEPTION) {
                throw new IOException("Simulated failure");
            }
            return failure == Failure.NULL ? null : Stream.of();
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * Create a module that opens as the given {@link ModuleReader}.
     *
     * @param moduleReader
     *            the reader that the module opens as, or null to break the {@link ModuleReference} contract
     * @return the module
     */
    private static ModuleReference moduleReference(final @Nullable ModuleReader moduleReader) {
        return new ModuleReference(ModuleDescriptor.newModule("test.module").build(), /* location = */ null) {
            @Override
            public ModuleReader open() {
                return moduleReader;
            }
        };
    }

    /**
     * The resources of a real module can be listed, tested for, located and read, through both an
     * {@link InputStream} and a {@link ByteBuffer}.
     *
     * @throws IOException
     *             if the module could not be opened or read
     */
    @Test
    public void theResourcesOfARealModuleCanBeListedAndRead() throws IOException {
        final var javaBase = ModuleFinder.ofSystem().find("java.base").orElseThrow();
        try (var moduleReader = ModuleReaderUtils.openModule(javaBase)) {
            final var resourcePaths = ModuleReaderUtils.list(moduleReader, "java.base", /* log = */ null);
            assertThat(resourcePaths).contains("java/lang/String.class");

            // The list has to be mutable, since the caller sorts it in place
            Collections.sort(resourcePaths);

            assertThat(ModuleReaderUtils.contains(moduleReader, "java/lang/String.class")).isTrue();
            assertThat(ModuleReaderUtils.contains(moduleReader, "no/such/Resource.class")).isFalse();
            assertThat(ModuleReaderUtils.find(moduleReader, "java/lang/String.class")).hasScheme("jrt");

            try (var inputStream = ModuleReaderUtils.open(moduleReader, "java/lang/String.class")) {
                final var header = ByteBuffer.wrap(inputStream.readNBytes(4));
                assertThat(header.getInt()).isEqualTo(CLASSFILE_MAGIC);
            }

            final var byteBuffer = ModuleReaderUtils.read(moduleReader, "java/lang/String.class");
            try {
                assertThat(byteBuffer.getInt()).isEqualTo(CLASSFILE_MAGIC);
            } finally {
                moduleReader.release(byteBuffer);
            }
        }
    }

    /**
     * A {@link ModuleReference} that returns null when it is opened is reported as the broken implementation it is,
     * rather than failing later with a {@link NullPointerException} that names nothing.
     */
    @Test
    public void aModuleThatOpensAsNullIsReported() {
        assertThatThrownBy(() -> ModuleReaderUtils.openModule(moduleReference(null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ModuleReference#open() returned null for module test.module");
    }

    /**
     * A {@link ModuleReader} that lists the contents of a module as null is treated as an empty module, rather than
     * aborting the scan, and the implementation that is at fault is named in the log.
     *
     * @throws IOException
     *             if the module could not be opened
     */
    @Test
    public void aModuleThatListsItsContentsAsNullIsTreatedAsEmpty() throws IOException {
        final var moduleReader = new FailingModuleReader(Failure.NULL);
        try (var reader = ModuleReaderUtils.openModule(moduleReference(moduleReader))) {
            final var log = new LogNode();
            final var resourcePaths = ModuleReaderUtils.list(reader, "test.module", log);
            assertThat(resourcePaths).isEmpty();
            // The empty list has to be mutable too, since the caller sorts whatever it gets in place, and
            // Collections#sort throws on an immutable list even when there is nothing to sort
            Collections.sort(resourcePaths);
            assertThat(log.toString()).contains("ModuleReader#list() returned null for module test.module")
                    .contains(FailingModuleReader.class.getName());
        }
    }

    /**
     * A {@link ModuleReader} that returns null or an empty {@link Optional} for a resource that was found by
     * listing the module is rejected, naming the path that could not be read.
     */
    @Test
    public void aResourceThatCannotBeReadIsRejected() throws IOException {
        for (final var failure : new Failure[] { Failure.NULL, Failure.EMPTY }) {
            final var moduleReader = new FailingModuleReader(failure);
            assertThatThrownBy(() -> ModuleReaderUtils.open(moduleReader, "some/Resource.class"))
                    .isInstanceOf(IOException.class).hasMessageContaining("ModuleReader#open");
            assertThatThrownBy(() -> ModuleReaderUtils.read(moduleReader, "some/Resource.class"))
                    .isInstanceOf(IOException.class).hasMessageContaining("ModuleReader#read");
            assertThatThrownBy(() -> ModuleReaderUtils.find(moduleReader, "some/Resource.class"))
                    .isInstanceOf(IOException.class).hasMessageContaining("ModuleReader#find");

            // A module that does not contain a resource is not an error, it just does not contain it
            assertThat(ModuleReaderUtils.contains(moduleReader, "some/Resource.class")).isFalse();
        }
    }

    /**
     * The {@link IOException} that every {@link ModuleReader} method can throw reaches the caller as an
     * {@link IOException} that names the call that failed, keeping the original exception as its cause. (The caller
     * is reading a resource, so an unchecked exception would escape the {@code throws IOException} that the caller
     * declares, and would be reported to the user as something other than the read failure that it is.)
     */
    @Test
    public void anIOExceptionFromAModuleReaderNamesTheCallThatFailed() {
        final var moduleReader = new FailingModuleReader(Failure.IO_EXCEPTION);

        assertThatThrownBy(() -> ModuleReaderUtils.list(moduleReader, "test.module", /* log = */ null))
                .isInstanceOf(IOException.class)
                .hasMessage("Could not call ModuleReader#list() for module test.module")
                .hasRootCauseMessage("Simulated failure");
        assertThatThrownBy(() -> ModuleReaderUtils.open(moduleReader, "some/Resource.class"))
                .isInstanceOf(IOException.class)
                .hasMessage("Could not call ModuleReader#open(String) for path some/Resource.class")
                .hasRootCauseMessage("Simulated failure");
        assertThatThrownBy(() -> ModuleReaderUtils.read(moduleReader, "some/Resource.class"))
                .isInstanceOf(IOException.class)
                .hasMessage("Could not call ModuleReader#read(String) for path some/Resource.class")
                .hasRootCauseMessage("Simulated failure");
        assertThatThrownBy(() -> ModuleReaderUtils.find(moduleReader, "some/Resource.class"))
                .isInstanceOf(IOException.class)
                .hasMessage("Could not call ModuleReader#find(String) for path some/Resource.class")
                .hasRootCauseMessage("Simulated failure");
        assertThatThrownBy(() -> ModuleReaderUtils.contains(moduleReader, "some/Resource.class"))
                .isInstanceOf(IOException.class)
                .hasMessage("Could not call ModuleReader#find(String) for path some/Resource.class")
                .hasRootCauseMessage("Simulated failure");
    }

    /**
     * The stream returned by {@link ModuleReader#list()} is closed once it has been read. For an exploded module,
     * that stream walks a directory tree, and it is closing the stream that closes the directories it opened, so
     * leaving it unclosed leaks a file handle per module.
     *
     * @throws IOException
     *             if the module could not be listed
     */
    @Test
    public void theStreamOfResourcePathsIsClosed() throws IOException {
        final var streamClosed = new AtomicBoolean();
        final var moduleReader = new ModuleReader() {
            @Override
            public Optional<URI> find(final String name) {
                return Optional.empty();
            }

            @Override
            public Stream<String> list() {
                return Stream.of("some/Resource.class").onClose(() -> streamClosed.set(true));
            }

            @Override
            public void close() {
                // Nothing to close
            }
        };
        assertThat(ModuleReaderUtils.list(moduleReader, "test.module", /* log = */ null))
                .containsExactly("some/Resource.class");
        assertThat(streamClosed).isTrue();
    }

    /**
     * A lazy {@link ModuleReader} that fails while its stream of resource paths is being read reports the failure
     * as an {@link IOException}. {@link ModuleReader#list()} is specified to wrap such a failure in an
     * {@link UncheckedIOException} and throw it from the stream operation that caused the read, so without this the
     * unchecked exception would escape past the {@code throws IOException} that the caller declares.
     */
    @Test
    public void anIOExceptionWhileReadingTheStreamOfResourcePathsIsReported() {
        final var moduleReader = new ModuleReader() {
            @Override
            public Optional<URI> find(final String name) {
                return Optional.empty();
            }

            @Override
            public Stream<String> list() {
                return Stream.of("some/Resource.class").map(path -> {
                    throw new UncheckedIOException(new IOException("Simulated failure"));
                });
            }

            @Override
            public void close() {
                // Nothing to close
            }
        };
        assertThatThrownBy(() -> ModuleReaderUtils.list(moduleReader, "test.module", /* log = */ null))
                .isInstanceOf(IOException.class).hasMessage("Could not list the contents of module test.module")
                .hasRootCauseMessage("Simulated failure");
    }
}
