import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Maps a 1 MB file, copies len bytes out of the mapping often enough for the copy to be JIT-compiled, truncates the
 * file to 4096 bytes, and copies len bytes from offset 65536, which is now beyond the end of the file.
 *
 * Expected: the copy throws an exception (InternalError, per the MappedByteBuffer spec "an unspecified exception").
 * Observed on aarch64 (Linux and macOS), len 100 or 8192: the JVM crashes with SIGBUS in StubRoutines::copy_byte_f.
 * Observed on x86-64: the InternalError is thrown only after copy() has returned, so the catch in copy() misses it.
 *
 * Run: java TruncatedMappingRepro.java [len] (len defaults to 100)
 */
public class TruncatedMappingRepro {
    static String copy(ByteBuffer buf, int off, byte[] dst, int len) {
        try {
            buf.get(off, dst, 0, len);
            return "ok";
        } catch (Throwable t) {
            return "caught in copy(): " + t;
        }
    }

    public static void main(String[] args) throws Exception {
        int len = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        Path file = Files.createTempFile("trunc", ".bin");
        Files.write(file, new byte[1 << 20]);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            byte[] dst = new byte[len];
            for (int i = 0; i < 200_000; i++) {
                copy(buf, (i * 64) & 0xFFFF, dst, len);
            }
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(4096);
            } catch (java.io.IOException e) {
                System.out.println("truncation refused: " + e); // Windows
                return;
            }
            String result;
            try {
                result = copy(buf, 65536, dst, len);
            } catch (Throwable t) {
                result = "NOT caught in copy(), thrown in main(): " + t;
            }
            System.out.println(result);
        } finally {
            // Windows cannot delete a file that is still mapped
            file.toFile().deleteOnExit();
        }
    }
}
