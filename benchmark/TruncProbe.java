import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read a memory mapping beyond the end of a file that was truncated after it was mapped, with one kind of read
 * given by args[1], and print what the read threw. args[2] is "cold" to read before the read is JIT-compiled, or
 * "hot" to read after.
 */
public class TruncProbe {
    static long sink;

    static final ThreadLocal<ByteBuffer> BOUNCE = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(8192));

    static void read(final String kind, final ByteBuffer buf, final int off) {
        switch (kind) {
        case "byte" -> sink += buf.get(off);
        case "short" -> sink += buf.getShort(off);
        case "int" -> sink += buf.getInt(off);
        case "long" -> sink += buf.getLong(off);
        case "bulkArray" -> buf.get(off, new byte[100], 0, 100);
        case "bulkHeapBuf" -> ByteBuffer.allocate(100).put(0, buf, off, 100);
        case "bulkDirectBuf" -> ByteBuffer.allocateDirect(100).put(0, buf, off, 100);
        case "bulkArray8k" -> buf.get(off, new byte[8192], 0, 8192);
        case "segCopy" -> MemorySegment.copy(MemorySegment.ofBuffer(buf), ValueLayout.JAVA_BYTE, off,
                new byte[8192], 0, 8192);
        case "viaDirect" -> {
            final var bounce = BOUNCE.get();
            bounce.put(0, buf, off, 8192);
            bounce.get(0, new byte[8192], 0, 8192);
        }
        case "longLoop" -> {
            final var dst = ByteBuffer.wrap(new byte[8192]).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 8192; i += 8) {
                dst.putLong(i, buf.getLong(off + i));
            }
        }
        default -> throw new IllegalArgumentException(kind);
        }
    }

    public static void main(final String[] args) throws Exception {
        final String kind = args[1];
        final boolean hot = args[2].equals("hot");
        final Path file = Files.createTempFile(Path.of(args[0]), "trunc", ".bin");
        Files.write(file, new byte[4 << 20]);
        try (var ch = FileChannel.open(file, StandardOpenOption.READ); var arena = Arena.ofShared()) {
            final var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena).asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (hot) {
                for (int r = 0; r < 300_000; r++) {
                    read(kind, buf, (r * 64) & 0xFFFF);
                }
            }
            try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(4096);
            }
            try {
                read(kind, buf, 1 << 20);
                System.out.println("RESULT " + kind + " " + args[2] + ": no exception");
            } catch (final Throwable t) {
                System.out.println("RESULT " + kind + " " + args[2] + ": " + t);
            }
        }
        Files.delete(file);
    }
}
