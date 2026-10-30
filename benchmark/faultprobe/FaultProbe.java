import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Maps a file, optionally runs one kind of copy often enough for it to be JIT-compiled, makes the pages beyond 32
 * MB unreadable in one of several ways (the "inducer"), then copies from 32 MB. Prints one RESULT line saying
 * whether the copy threw at the copy ("at-read"), threw after the method that did the copy had returned ("late"),
 * did not throw ("no-error"), or whether the inducer itself failed ("induce-failed", e.g. a refused truncation). A
 * JVM crash prints no RESULT line.
 *
 * <p>
 * Args: file map(legacy|arena) kind size(bytes) hot(true|false) inducer...
 *
 * <p>
 * Inducers: "setLength [path]" truncates to 4096 bytes through a new RandomAccessFile (on the mapped file, or on
 * path, which may name the same file another way); "channelTruncate" does it through a new writable FileChannel;
 * "cmd prog args..." runs a command and fails if it exits nonzero.
 *
 * <p>
 * "--create file sizeMB" writes a file of 0x11 bytes.
 */
public class FaultProbe {
    static volatile long sink;

    static final long FAULT_OFFSET = 32L << 20;

    static String readCatching(final Common.Copy c, final long off, final byte[] dst, final int len) {
        try {
            c.copy(off, dst, len);
            return null;
        } catch (final Throwable t) {
            return t.toString();
        }
    }

    /** Copies from the first 64 KB only, so that the pages at FAULT_OFFSET are never touched before the fault. */
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

    /** What a fresh look at the file shows after the inducer ran: its size, or why it could not be read. */
    static String after(final Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return " [after: size " + ch.size() + "]";
        } catch (final Throwable t) {
            return " [after: " + t + "]";
        }
    }

    /** Returns null if the pages were made unreadable, else why not. */
    static String induce(final Path file, final String[] args) throws Exception {
        switch (args[0]) {
        case "setLength":
            try (RandomAccessFile raf = new RandomAccessFile((args.length > 1 ? Path.of(args[1]) : file).toFile(),
                    "rw")) {
                raf.setLength(4096);
                return null;
            } catch (final IOException e) {
                return e.toString();
            }
        case "channelTruncate":
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(4096);
                return null;
            } catch (final IOException e) {
                return e.toString();
            }
        case "cmd": {
            final Process p = new ProcessBuilder(Arrays.copyOfRange(args, 1, args.length)).redirectErrorStream(true)
                    .start();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            }
            final int code = p.waitFor();
            final String text = out.toString().replaceAll("\\s+", " ").trim();
            return code == 0 ? null : "exit " + code + ": " + text;
        }
        default:
            throw new IllegalArgumentException(args[0]);
        }
    }

    public static void main(final String[] args) throws Exception {
        if (args[0].equals("--create")) {
            final byte[] chunk = new byte[1 << 20];
            Arrays.fill(chunk, (byte) 0x11);
            try (var out = Files.newOutputStream(Path.of(args[1]))) {
                for (int i = Integer.parseInt(args[2]); i > 0; i--) {
                    out.write(chunk);
                }
            }
            return;
        }
        final Path file = Path.of(args[0]);
        final String map = args[1], kind = args[2];
        final int len = Integer.parseInt(args[3]);
        final boolean hot = Boolean.parseBoolean(args[4]);
        if (map.equals("none")) {
            // The control: whether the inducer works on a file that is not mapped
            final String failed = induce(file, Arrays.copyOfRange(args, 5, args.length));
            System.out.println("RESULT " + (failed == null ? "induced" : "induce-failed " + failed) + after(file));
            return;
        }

        final Common.Ctx ctx = new Common.Ctx(file, map);
        final Common.Copy copy = kind.startsWith("reader")
                ? (Common.Copy) Class.forName("ReaderOps")
                        .getMethod("make", String.class, java.nio.ByteBuffer.class).invoke(null, kind, ctx.buf)
                : Common.make(kind, ctx);
        final byte[] dst = new byte[Math.max(len, 8)];
        if (hot) {
            warm(copy, dst, len);
        }
        final String failed = induce(file, Arrays.copyOfRange(args, 5, args.length));
        if (failed != null) {
            System.out.println("RESULT induce-failed " + failed);
            return;
        }
        Arrays.fill(dst, (byte) 0x55);
        try {
            final String atRead = readCatching(copy, FAULT_OFFSET, dst, len);
            if (atRead != null) {
                System.out.println("RESULT at-read " + atRead);
                return;
            }
            // Give a pending asynchronous error every chance to be delivered: a sleep, then allocation
            Thread.sleep(20);
            for (int i = 0; i < 10_000; i++) {
                sink += System.identityHashCode(new Object());
            }
            System.out.println("RESULT no-error " + describe(dst) + after(file));
        } catch (final Throwable t) {
            System.out.println(
                    "RESULT late " + t + " (thrown at " + t.getStackTrace()[0] + "; " + describe(dst) + ")");
        }
    }
}
