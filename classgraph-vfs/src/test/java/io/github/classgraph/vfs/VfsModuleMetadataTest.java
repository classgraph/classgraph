package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Module metadata does not require a retained, array-sized resource buffer. */
public class VfsModuleMetadataTest {
    /**
     * Reading one native file must not populate the root's cached recursive listing.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void nativePointReadDoesNotCacheARecursiveListing(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("first"), "first");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            assertThat(root.getEntry("missing")).isNull();
            assertThat(Files.readString(root.asFileSystem().getPath("/first"))).isEqualTo("first");
            Files.writeString(dir.resolve("second"), "second");
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactly("first", "second");
        }
    }

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
     * Transfers that continue where the last one stopped, whether because the caller copies a chunk at a time or
     * because the target took only part of a transfer, read on through one stream, and read each byte once.
     *
     * @throws IOException
     *             if the resource cannot be read.
     */
    @Test
    public void consecutiveTransfersReadThroughOneStream() throws IOException {
        final var length = 100_000L;
        final var bytesRead = new AtomicLong();
        final var streamsOpened = new AtomicLong();
        try (var vfs = new Vfs()) {
            final var path = vfs.open(streamingModule(length, bytesRead, streamsOpened)).asFileSystem()
                    .getPath("/payload");
            try (var channel = FileChannel.open(path)) {
                final var bytesWritten = new AtomicLong();
                final var target = new WritableByteChannel() {
                    @Override
                    public int write(final ByteBuffer source) {
                        // Take at most 1000 bytes, so that every transfer of 4096 bytes ends early
                        final var count = Math.min(source.remaining(), 1000);
                        for (var i = 0; i < count; i++) {
                            assertThat(source.get()).isEqualTo((byte) 7);
                        }
                        bytesWritten.addAndGet(count);
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
                // Opening the channel opens a stream for its positional reads, which the transfers do not use
                final var streamsOpenedBefore = streamsOpened.get();
                var position = 0L;
                while (position < length) {
                    final var numTransferred = channel.transferTo(position, 4096, target);
                    assertThat(numTransferred).isPositive();
                    position += numTransferred;
                }
                assertThat(channel.transferTo(position, 4096, target)).isZero();
                assertThat(bytesWritten.get()).isEqualTo(length);
                assertThat(streamsOpened.get() - streamsOpenedBefore).isEqualTo(1);
                assertThat(bytesRead.get()).isEqualTo(length);

                // A transfer from an earlier position has to start again from the beginning
                assertThat(channel.transferTo(10, 500, target)).isEqualTo(500);
                assertThat(streamsOpened.get() - streamsOpenedBefore).isEqualTo(2);
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
        return streamingModule(length, bytesRead, new AtomicLong());
    }

    /**
     * Create a synthetic module with a large streaming resource and no whole-resource buffer operation.
     *
     * @param length
     *            the resource length.
     * @param bytesRead
     *            the counter for bytes actually read.
     * @param streamsOpened
     *            the counter for streams opened on the resource.
     * @return the module reference.
     */
    private static ModuleReference streamingModule(final long length, final AtomicLong bytesRead,
            final AtomicLong streamsOpened) {
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
                        streamsOpened.incrementAndGet();
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
                        throw new AssertionError("Point reads must not enumerate module resources");
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
