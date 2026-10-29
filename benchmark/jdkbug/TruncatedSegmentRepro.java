import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Maps a 1 MB file into a shared Arena, copies len bytes out of the segment with MemorySegment.copy often enough
 * for the copy to be JIT-compiled, truncates the file to 4096 bytes, and copies len bytes from offset 65536, which
 * is now beyond the end of the file.
 *
 * Expected: MemorySegment.copy throws an exception, inside the try block that encloses the call. Observed on
 * aarch64 (Linux and macOS), len 100 or 8192: the JVM crashes with SIGBUS in StubRoutines::copy_byte_f. Observed on
 * x86-64: the InternalError is thrown only after copy() has returned, so the catch in copy() misses it.
 *
 * Run (JDK 22 or later): java TruncatedSegmentRepro.java [len] (len defaults to 100)
 */
public class TruncatedSegmentRepro {
    static String copy(MemorySegment seg, long off, byte[] dst, int len) {
        try {
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, off, dst, 0, len);
            return "ok";
        } catch (Throwable t) {
            return "caught in copy(): " + t;
        }
    }

    public static void main(String[] args) throws Exception {
        int len = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        Path file = Files.createTempFile("trunc", ".bin");
        Files.write(file, new byte[1 << 20]);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ); Arena arena = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            byte[] dst = new byte[len];
            for (int i = 0; i < 200_000; i++) {
                copy(seg, (i * 64) & 0xFFFF, dst, len);
            }
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(4096);
            }
            String result;
            try {
                result = copy(seg, 65536, dst, len);
            } catch (Throwable t) {
                result = "NOT caught in copy(), thrown in main(): " + t;
            }
            System.out.println(result);
        } finally {
            Files.delete(file);
        }
    }
}
