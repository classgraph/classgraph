package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
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
     * Transfers beyond the array limit stream independently, preserve position, and stop at a partial target.
     *
     * @throws IOException
     *             if the resource cannot be read.
     */
    @Test
    public void transferDoesNotRetainItsPrefix() throws IOException {
        final var length = (long) Integer.MAX_VALUE + 4096;
        final var bytesRead = new AtomicLong();
        try (var vfs = new Vfs()) {
            final var path = vfs.open(streamingModule(length, bytesRead)).asFileSystem().getPath("/payload");
            try (var channel = FileChannel.open(path)) {
                channel.position(11);
                final var target = new WritableByteChannel() {
                    @Override
                    public int write(final ByteBuffer source) {
                        final var count = Math.min(source.remaining(), 3);
                        for (var i = 0; i < count; i++) {
                            assertThat(source.get()).isEqualTo((byte) 7);
                        }
                        return count;
                    }

                    @Override
                    public boolean isOpen() {
                        return true;
                    }

                    @Override
                    public void close() {
                        // This synthetic target owns no resources.
                    }
                };
                assertThat(channel.transferTo((long) Integer.MAX_VALUE + 17, 32, target)).isEqualTo(3);
                assertThat(bytesRead.get()).isEqualTo(32);
                assertThat(channel.position()).isEqualTo(11);
                assertThat(channel.transferTo(length, 32, target)).isZero();
                assertThat(channel.transferTo(Long.MAX_VALUE, 32, target)).isZero();
                assertThat(bytesRead.get()).isEqualTo(32);
                final var prefix = ByteBuffer.allocate(64);
                assertThat(channel.read(prefix, 0)).isEqualTo(64);
                assertThat(bytesRead.get()).isGreaterThan(32);
                assertThat(prefix.array()).containsOnly((byte) 7);
                assertThat(channel.position()).isEqualTo(11);
            }
        }
    }

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
