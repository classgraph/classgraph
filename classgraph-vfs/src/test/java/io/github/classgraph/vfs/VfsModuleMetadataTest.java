package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Module metadata does not require a retained, array-sized resource buffer. */
public class VfsModuleMetadataTest {
    /**
     * Unknown sizes may exceed the array limit, and successful attribute snapshots are counted only once.
     *
     * @throws IOException
     *             if the module cannot be read.
     */
    @Test
    public void largeUnknownSizeIsStreamedAndMemoized() throws IOException {
        final var length = (long) Integer.MAX_VALUE + 1024;
        final var bytesRead = new AtomicLong();
        try (var vfs = new Vfs()) {
            final var path = vfs.open(streamingModule(length, bytesRead)).asFileSystem().getPath("/payload");
            final var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            assertThat(bytesRead.get()).isZero();
            assertThat(attrs.size()).isEqualTo(length);
            assertThat(bytesRead.get()).isEqualTo(length);
            assertThat(attrs.size()).isEqualTo(length);
            assertThat(bytesRead.get()).isEqualTo(length);
        }
    }

    /**
     * Create a synthetic module with a large streaming resource and no whole-resource buffer operation.
     *
     * @param length
     *            the resource length.
     * @param bytesRead
     *            the counter for bytes actually read.
     * @return the module reference.
     */
    private static ModuleReference streamingModule(final long length, final AtomicLong bytesRead) {
        final var uri = URI.create("test-module:/streaming");
        return new ModuleReference(ModuleDescriptor.newModule("test.streaming").build(), uri) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(final String name) {
                        return name.equals("payload") ? Optional.of(uri.resolve("payload")) : Optional.empty();
                    }

                    @Override
                    public Optional<InputStream> open(final String name) {
                        if (!name.equals("payload")) {
                            return Optional.empty();
                        }
                        return Optional.of(new InputStream() {
                            private long position;

                            @Override
                            public int read() {
                                if (position >= length) {
                                    return -1;
                                }
                                position++;
                                bytesRead.incrementAndGet();
                                return 7;
                            }

                            @Override
                            public int read(final byte[] buffer, final int offset, final int count) {
                                if (count == 0) {
                                    return 0;
                                }
                                if (position >= length) {
                                    return -1;
                                }
                                final var size = (int) Math.min(count, length - position);
                                Arrays.fill(buffer, offset, offset + size, (byte) 7);
                                position += size;
                                bytesRead.addAndGet(size);
                                return size;
                            }

                            @Override
                            public long skip(final long count) {
                                final var skipped = Math.min(Math.max(count, 0), length - position);
                                position += skipped;
                                return skipped;
                            }
                        });
                    }

                    @Override
                    public Optional<ByteBuffer> read(final String name) {
                        throw new AssertionError(
                                "Metadata must stream, not request a whole-resource buffer: " + name);
                    }

                    @Override
                    public Stream<String> list() {
                        return Stream.of("payload");
                    }

                    @Override
                    public void close() {
                        // This synthetic reader owns no resources.
                    }
                };
            }
        };
    }
}
