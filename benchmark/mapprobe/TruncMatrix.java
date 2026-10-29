import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Maps a 1 MB file, optionally runs one kind of copy often enough for it to be JIT-compiled, truncates the file to
 * 4096 bytes, and copies from beyond the new end. Prints one RESULT line saying whether the copy threw at the copy
 * ("at-read"), threw after the method that did the copy had returned ("late"), or did not throw ("no-error"). A JVM
 * crash prints no RESULT line.
 *
 * <p>
 * Args: dir map(legacy|arena) kind size(bytes, or "tail") warm(true|false). "tail" copies 72 bytes so that only the
 * last long of the copy is beyond the end of the file.
 */
public class TruncMatrix {
    static volatile long sink;

    /** Does one copy inside a catch, like RandomAccessByteBufferReader does. */
    static String readCatching(final Common.Copy c, final long off, final byte[] dst, final int len) {
        try {
            c.copy(off, dst, len);
            return null;
        } catch (final Throwable t) {
            return t.toString();
        }
    }

    static void warm(final Common.Copy c, final byte[] dst, final int len) {
        for (int r = 0; r < 200_000; r++) {
            final String e = readCatching(c, (r * 64L) & 0xFFFF, dst, len);
            if (e != null) {
                throw new AssertionError(e);
            }
        }
    }

    static String describe(final byte[] dst) {
        int filler = 0, content = 0, zero = 0;
        for (final byte b : dst) {
            if (b == 0x55) {
                filler++;
            } else if (b == 0x11) {
                content++;
            } else if (b == 0) {
                zero++;
            }
        }
        return "dst: " + content + " file bytes, " + zero + " zero, " + filler + " unwritten, of " + dst.length;
    }

    public static void main(final String[] args) throws Exception {
        final Path dir = Path.of(args[0]);
        final String map = args[1], kind = args[2];
        final boolean tail = args[3].equals("tail");
        final int len = tail ? 72 : Integer.parseInt(args[3]);
        final long off = tail ? 4096 - 64 : 65536;
        final boolean hot = Boolean.parseBoolean(args[4]);

        final Path file = dir.resolve("trunc.bin");
        final byte[] content = new byte[1 << 20];
        Arrays.fill(content, (byte) 0x11);
        Files.write(file, content);

        final Common.Ctx ctx = new Common.Ctx(file, map);
        if (kind.equals("heap")) {
            ctx.loadHeap();
        }
        final Common.Copy copy = Common.make(kind, ctx);
        final byte[] dst = new byte[Math.max(len, 8)];
        if (hot) {
            warm(copy, dst, len);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(4096);
        } catch (final IOException e) {
            System.out.println("RESULT truncate-refused " + e);
            return;
        }
        Arrays.fill(dst, (byte) 0x55);
        try {
            final String atRead = readCatching(copy, off, dst, len);
            if (atRead != null) {
                System.out.println("RESULT at-read " + atRead);
                return;
            }
            // Give a pending asynchronous error every chance to be delivered: a sleep, then allocation
            Thread.sleep(20);
            for (int i = 0; i < 10_000; i++) {
                sink += System.identityHashCode(new Object());
            }
            System.out.println("RESULT no-error " + describe(dst));
        } catch (final Throwable t) {
            System.out.println(
                    "RESULT late " + t + " (thrown at " + t.getStackTrace()[0] + "; " + describe(dst) + ")");
        }
    }
}
