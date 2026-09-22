package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * {@link NestedJarHandler#readAllBytesAsArray(java.io.InputStream, long)}.
 */
public class ReadAllBytesAsArrayTest {
    /**
     * A stream that returns zero from a read into a buffer with room left in it does not make the buffer grow, since
     * the buffer is not full. If it grew on every such read, a few dozen of them would grow it to the maximum array
     * size to hold a handful of bytes.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamThatReturnsZeroFromAReadDoesNotGrowTheBuffer() throws IOException {
        final byte[] content = new byte[64];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(content) {
            /** Whether the next read of a non-empty range returns zero. */
            private boolean returnZero = true;

            @Override
            public synchronized int read(final byte[] b, final int off, final int len) {
                returnZero = !returnZero;
                return len > 0 && !returnZero ? 0 : super.read(b, off, Math.min(len, 1));
            }
        };
        assertThat(NestedJarHandler.readAllBytesAsArray(inputStream, -1L)).containsExactly(content);
    }
}
