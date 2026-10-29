import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Time copying ranges of a warm memory-mapped file into heap arrays, by a bulk get, by a loop of getLong reads,
 * and by a positioned FileChannel read, for a range of copy sizes. args[0] is the directory for the temporary file.
 */
public class CopyBench {
    static long sink;

    static void bulk(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        buf.get(off, dst, 0, len);
    }

    static void longLoop(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        final var out = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
        var i = 0;
        for (; i + 8 <= len; i += 8) {
            out.putLong(i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            dst[i] = buf.get(off + i);
        }
    }

    public static void main(final String[] args) throws Exception {
        final int fileSize = 64 << 20;
        final Path file = Files.createTempFile(Path.of(args[0]), "copy", ".bin");
        final var content = new byte[fileSize];
        new java.util.Random(1).nextBytes(content);
        Files.write(file, content);
        try (var ch = FileChannel.open(file, StandardOpenOption.READ); var arena = Arena.ofShared()) {
            final var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena).asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (final int len : new int[] { 512, 2048, 8192, 32768 }) {
                final var dst = new byte[len];
                final int numCopies = (int) (2_000_000_000L / len / 4);
                for (final String method : new String[] { "bulk", "longLoop", "channel", "bulk", "longLoop",
                        "channel" }) {
                    final long start = System.nanoTime();
                    var off = 0;
                    for (int r = 0; r < numCopies; r++) {
                        off = (off + 7919 * 64) & (fileSize - 65536 - 1);
                        switch (method) {
                        case "bulk" -> bulk(buf, off, dst, len);
                        case "longLoop" -> longLoop(buf, off, dst, len);
                        default -> ch.read(ByteBuffer.wrap(dst), off);
                        }
                        sink += dst[len - 1];
                    }
                    final double nsPerKb = (System.nanoTime() - start) / (double) numCopies / (len / 1024.0);
                    System.out.printf("RESULT len=%6d %-8s %8.1f ns/KB%n", len, method, nsPerKb);
                }
            }
        }
        Files.delete(file);
        System.out.println(sink == 42 ? "" : " ");
    }
}
