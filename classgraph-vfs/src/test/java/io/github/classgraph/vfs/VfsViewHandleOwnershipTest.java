package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A filesystem view owns its open handles, not the root or its other views. */
public class VfsViewHandleOwnershipTest {
    /**
     * Failed cleanup still drains every handle, preserving fatal errors and leaving borrowed roots open.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation fails.
     */
    @Test
    public void failedCleanupStillDrainsHandles(@TempDir final Path dir) throws IOException {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            for (final var fatal : new boolean[] { false, true }) {
                final var view = (VfsFileSystem) root.asFileSystem();
                final var closes = new AtomicInteger();
                final var ioFailure = new IOException("synthetic close failure");
                final var vmFailure = new OutOfMemoryError("synthetic fatal close failure");
                for (var handleIndex = 0; handleIndex < 2; handleIndex++) {
                    final var failFatally = fatal && handleIndex == 1;
                    view.register(new Closeable() {
                        @Override
                        public void close() throws IOException {
                            closes.incrementAndGet();
                            view.unregister(this);
                            if (failFatally) {
                                throw vmFailure;
                            }
                            throw ioFailure;
                        }
                    });
                }
                assertThatThrownBy(view::close).isSameAs(fatal ? vmFailure : ioFailure);
                assertThat(closes.get()).isEqualTo(2);
                assertThat(root.isClosed()).isFalse();
                view.close();
                assertThat(closes.get()).isEqualTo(2);
            }
        }
    }

    /**
     * Every kind of view handle closes, while direct root reads and a replacement view remain usable.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void borrowedViewClosesOnlyItsOwnHandles(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), "content");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            final var view = root.asFileSystem();
            final var path = view.getPath("/file");
            try (var direct = Objects.requireNonNull(root.getEntry("file")).open();
                    var channel = FileChannel.open(path);
                    var bytes = Files.newByteChannel(path);
                    var input = Files.newInputStream(path);
                    var listing = Files.newDirectoryStream(view.getPath("/"))) {
                final var iterator = listing.iterator();
                assertThat(iterator.hasNext()).isTrue();
                view.close();
                assertThat(channel.isOpen()).isFalse();
                assertThat(bytes.isOpen()).isFalse();
                assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
                        .isInstanceOf(ClosedChannelException.class);
                assertThatThrownBy(input::read).isInstanceOf(IOException.class);
                assertThat(iterator.next()).isEqualTo(path);
                assertThat(iterator.hasNext()).isFalse();
                assertThat(root.isClosed()).isFalse();
                assertThat(direct.read()).isEqualTo('c');
                try (var replacement = root.asFileSystem();
                        var other = FileChannel.open(replacement.getPath("/file"))) {
                    view.close();
                    assertThat(replacement.isOpen()).isTrue();
                    assertThat(other.read(ByteBuffer.allocate(1))).isEqualTo(1);
                }
            }
        }
    }

    /**
     * A channel racing closure is either rejected or returned closed; no registration can escape the drain.
     *
     * @param dir
     *            the fixture directory.
     * @throws Exception
     *             if fixture creation or a concurrent operation fails.
     */
    @Test
    public void registrationRacingClosureCannotLeakAChannel(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("file"), "content");
        final var executor = Executors.newFixedThreadPool(2);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            for (var iteration = 0; iteration < 50; iteration++) {
                final var view = root.asFileSystem();
                final var path = view.getPath("/file");
                final var barrier = new CyclicBarrier(2);
                final var opener = executor.submit(() -> {
                    barrier.await();
                    try {
                        return FileChannel.open(path);
                    } catch (final ClosedFileSystemException expected) {
                        assertThat(expected).isNotNull();
                        return null;
                    }
                });
                final var closer = executor.submit(() -> {
                    barrier.await();
                    view.close();
                    return true;
                });
                assertThat(closer.get(10, TimeUnit.SECONDS)).isTrue();
                final var channel = opener.get(10, TimeUnit.SECONDS);
                if (channel != null) {
                    assertThat(channel.isOpen()).isFalse();
                    channel.close();
                }
                assertThat(root.isClosed()).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
