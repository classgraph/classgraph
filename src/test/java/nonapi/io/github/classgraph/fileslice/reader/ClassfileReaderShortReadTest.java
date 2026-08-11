package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests that a {@link ClassfileReader} built on an {@link InputStream} completes a short read.
 *
 * <p>
 * {@link InputStream#read(byte[], int, int)} is not required to transfer the whole of the requested range in a
 * single call, and the channel-backed streams that a module and a directory are read through really can transfer
 * less. The reader used to issue a single read and throw {@code IOException: Buffer underflow} if it came up short.
 */
public class ClassfileReaderShortReadTest {
    /** An {@link InputStream} that never transfers more than one byte per read, however many were asked for. */
    private static final class ShortReadInputStream extends InputStream {
        private final InputStream wrapped;

        ShortReadInputStream(final InputStream wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read() throws IOException {
            return wrapped.read();
        }

        @Override
        public int read(final byte[] buf, final int off, final int len) throws IOException {
            return len == 0 ? 0 : wrapped.read(buf, off, 1);
        }

        @Override
        public void close() throws IOException {
            wrapped.close();
        }
    }

    /** Content with a different value every few bytes, so content read at the wrong offset does not still match. */
    private static byte[] content(final int length) {
        final byte[] content = new byte[length];
        for (int i = 0; i < length; i++) {
            content[i] = (byte) (i / 3);
        }
        return content;
    }

    private static ClassfileReader shortReadReader(final byte[] content) throws IOException {
        return new ClassfileReader(new ShortReadInputStream(new ByteArrayInputStream(content)), null);
    }

    /** Every fixed-size read must return the whole value, not throw once the first read comes up short. */
    @Test
    public void everyValueIsReadInFullFromAShortReadingStream() throws IOException {
        final byte[] pattern = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF };
        try (ClassfileReader reader = shortReadReader(pattern)) {
            assertThat(reader.readLong(0)).isEqualTo(0x0123456789ABCDEFL);
            assertThat(reader.readInt(0)).isEqualTo(0x01234567);
            assertThat(reader.readUnsignedShort(0)).isEqualTo(0x0123);
            assertThat(reader.readUnsignedByte(0)).isEqualTo(0x01);
        }
    }

    /** A string longer than one read must be read in full. */
    @Test
    public void aStringIsReadInFullFromAShortReadingStream() throws IOException {
        final byte[] data = "HelloWorld".getBytes(StandardCharsets.UTF_8);
        try (ClassfileReader reader = shortReadReader(data)) {
            assertThat(reader.readString(10)).isEqualTo("HelloWorld");
        }
    }

    /** Content longer than the reader's 16kb initial buffer must be read in full, growing the buffer as it goes. */
    @Test
    public void contentLongerThanTheInitialBufferIsReadInFull() throws IOException {
        final int length = 40000;
        final byte[] content = content(length);
        try (ClassfileReader reader = shortReadReader(content)) {
            final byte[] readBack = new byte[length];
            assertThat(reader.read(0, readBack, 0, length)).isEqualTo(length);
            assertThat(readBack).isEqualTo(content);
        }
    }
}
