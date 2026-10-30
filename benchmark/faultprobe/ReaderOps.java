import java.io.EOFException;
import java.nio.ByteBuffer;

import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;

/** Copies through the reader that classgraph-vfs reads a mapping with. Compiled only on JDK 25 and later. */
public final class ReaderOps {
    public static Common.Copy make(final String kind, final ByteBuffer buf) {
        final RandomAccessByteBufferReader r = new RandomAccessByteBufferReader(buf, 0L, buf.capacity());
        switch (kind) {
        case "readerRead":
            return (off, dst, len) -> {
                if (r.read(off, dst, 0, len) != len) {
                    throw new EOFException();
                }
            };
        case "readerLong":
            return (off, dst, len) -> Common.LONGS.set(dst, 0, r.readLong(off));
        default:
            throw new IllegalArgumentException(kind);
        }
    }
}
