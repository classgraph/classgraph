package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.fileslice.ArraySlice;

/**
 * Tests {@link ClassfileReader#skip(int)}.
 *
 * <p>
 * The number of bytes to skip is read from the classfile -- it is the length of an attribute that is not of
 * interest -- so a corrupt classfile can ask for a skip of almost 2GB, or, since an attribute length is an unsigned
 * 32-bit value and no classfile can be that long anyway, for a skip of a negative number of bytes. The target
 * position used to be computed in int arithmetic, so a skip of close to 2GB wrapped the read position negative
 * without throwing anything at all, and the read after it hit the buffer at a negative index.
 */
public class ClassfileReaderSkipTest {
    /** Eight bytes, with a different value in every one. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** A reader over a fixed array, which cannot grow its buffer. */
    private static ClassfileReader arrayReader() throws IOException {
        return new ClassfileReader(new ArraySlice(PATTERN.clone(), /* isDeflatedZipEntry = */ false,
                /* inflatedLengthHint = */ 0L, /* nestedJarHandler = */ null), null);
    }

    /** A reader over an {@link java.io.InputStream}, which grows its buffer as it reads. */
    private static ClassfileReader streamReader() throws IOException {
        return new ClassfileReader(new ByteArrayInputStream(PATTERN.clone()), null);
    }

    /** A skip past the end of a fixed array is rejected, and leaves the read position where it was. */
    @Test
    public void aSkipPastTheEndOfAnArrayIsRejected() throws IOException {
        try (ClassfileReader reader = arrayReader()) {
            assertSkipsPastTheEndAreRejected(reader);
        }
    }

    /** A skip past the end of a stream is rejected, and leaves the read position where it was. */
    @Test
    public void aSkipPastTheEndOfAStreamIsRejected() throws IOException {
        try (ClassfileReader reader = streamReader()) {
            assertSkipsPastTheEndAreRejected(reader);
        }
    }

    /**
     * Read the first four bytes, then check that every skip that runs past the end of the content throws
     * {@link IOException}, and that the reader can still read the rest of the content afterwards.
     *
     * @param reader
     *            the reader to check
     * @throws IOException
     *             if the content could not be read
     */
    private static void assertSkipsPastTheEndAreRejected(final ClassfileReader reader) throws IOException {
        assertThat(reader.readInt()).isEqualTo(0x01234567);

        assertThatThrownBy(() -> reader.skip(PATTERN.length)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> reader.skip(Integer.MAX_VALUE)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> reader.skip(-1)).isInstanceOf(IOException.class)
                .hasMessage("Tried to skip a negative number of bytes");

        // The read position did not move, so the reads that stay within the content still succeed
        assertThat(reader.currPos()).isEqualTo(4);
        assertThat(reader.readInt()).isEqualTo(0x89ABCDEF);
    }

    /** A skip that stays within the content moves the read position by exactly that many bytes. */
    @Test
    public void aSkipWithinTheContentMovesTheReadPosition() throws IOException {
        try (ClassfileReader reader = streamReader()) {
            reader.skip(4);
            assertThat(reader.currPos()).isEqualTo(4);
            assertThat(reader.readInt()).isEqualTo(0x89ABCDEF);
        }
    }
}
