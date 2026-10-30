import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        case "readerReadBB": {
            // Into a heap ByteBuffer, through read(long, ByteBuffer, int, int)
            final ByteBuffer[] out = new ByteBuffer[1];
            return (off, dst, len) -> {
                if (out[0] == null || out[0].array() != dst) {
                    out[0] = ByteBuffer.wrap(dst);
                }
                if (r.read(off, out[0], 0, len) != len) {
                    throw new EOFException();
                }
            };
        }
        case "readerReadDirect": {
            // Into a direct ByteBuffer
            final ByteBuffer out = ByteBuffer.allocateDirect(1 << 20);
            return (off, dst, len) -> {
                if (r.read(off, out, 0, len) != len) {
                    throw new EOFException();
                }
                dst[len - 1] = out.get(len - 1);
            };
        }
        case "readerByte":
            return (off, dst, len) -> dst[0] = r.readByte(off);
        case "readerInt":
            return (off, dst, len) -> ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN).putInt(0, r.readInt(off));
        case "readerLong":
            return (off, dst, len) -> Common.LONGS.set(dst, 0, r.readLong(off));
        default:
            throw new IllegalArgumentException(kind);
        }
    }
}
