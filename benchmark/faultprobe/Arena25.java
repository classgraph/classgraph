import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** The copy methods that need java.lang.foreign (JDK 22 and later), over a file mapped into a shared arena. */
public final class Arena25 implements Common.ArenaOps {
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    private Arena arena;

    private MemorySegment seg;

    @Override
    public ByteBuffer map(final FileChannel ch) throws IOException {
        arena = Arena.ofShared();
        seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        return seg.asByteBuffer();
    }

    @Override
    public void close() {
        arena.close();
    }

    @Override
    public Common.Copy segCopy() {
        final MemorySegment s = seg;
        return (off, dst, len) -> MemorySegment.copy(s, ValueLayout.JAVA_BYTE, off, dst, 0, len);
    }

    @Override
    public Common.Copy segLongLoop() {
        final MemorySegment s = seg;
        return (off, dst, len) -> {
            int i = 0;
            for (; i + Long.BYTES <= len; i += Long.BYTES) {
                Common.LONGS.set(dst, i, s.get(LONG, off + i));
            }
            for (; i < len; i++) {
                dst[i] = s.get(ValueLayout.JAVA_BYTE, off + i);
            }
        };
    }
}
