import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Ways of copying a range of a file into a heap array, shared by TruncMatrix and ReadBench. Compiles on JDK 17; the
 * methods that need java.lang.foreign are in Arena25, which is compiled only on JDK 22 and later.
 */
public final class Common {
    /** Copies len bytes at off in the file into dst[0..len). */
    public interface Copy {
        void copy(long off, byte[] dst, int len) throws IOException;
    }

    /** The methods that need java.lang.foreign. */
    public interface ArenaOps {
        ByteBuffer map(FileChannel ch) throws IOException;

        Copy segCopy();

        Copy segLongLoop();

        void close();
    }

    static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** A file, a channel open on it, and a mapping of it. */
    public static final class Ctx {
        final Path file;
        final FileChannel ch;
        final ByteBuffer buf;
        final ArenaOps arena;
        byte[] heap;

        Ctx(final Path file, final String map) throws Exception {
            this.file = file;
            this.ch = FileChannel.open(file, StandardOpenOption.READ);
            if (map.equals("arena")) {
                arena = (ArenaOps) Class.forName("Arena25").getDeclaredConstructor().newInstance();
                buf = arena.map(ch).order(ByteOrder.LITTLE_ENDIAN);
            } else {
                arena = null;
                buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            }
        }

        /** Reads the whole file into a heap array, and returns the time it took in ms. */
        double loadHeap() throws IOException {
            final long start = System.nanoTime();
            heap = Files.readAllBytes(file);
            return (System.nanoTime() - start) / 1e6;
        }

        /** Closes the channel, and unmaps the file if it was mapped into an arena. */
        void close() throws IOException {
            if (arena != null) {
                arena.close();
            }
            ch.close();
        }
    }

    static void readFully(final FileChannel ch, final ByteBuffer dst, final long off) throws IOException {
        long pos = off;
        while (dst.hasRemaining()) {
            final int n = ch.read(dst, pos);
            if (n < 0) {
                throw new EOFException("Read past the end of the file at " + pos);
            }
            pos += n;
        }
    }

    /** The copy that RandomAccessByteBufferReader uses off Windows. */
    static void longLoop(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        final ByteBuffer out = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            out.putLong(i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            out.put(i, buf.get(off + i));
        }
    }

    static void longLoop4(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        final ByteBuffer out = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        for (; i + 32 <= len; i += 32) {
            final long a = buf.getLong(off + i);
            final long b = buf.getLong(off + i + 8);
            final long c = buf.getLong(off + i + 16);
            final long d = buf.getLong(off + i + 24);
            out.putLong(i, a);
            out.putLong(i + 8, b);
            out.putLong(i + 16, c);
            out.putLong(i + 24, d);
        }
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            out.putLong(i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            out.put(i, buf.get(off + i));
        }
    }

    static void longLoop8(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        int i = 0;
        for (; i + 64 <= len; i += 64) {
            final long a = buf.getLong(off + i);
            final long b = buf.getLong(off + i + 8);
            final long c = buf.getLong(off + i + 16);
            final long d = buf.getLong(off + i + 24);
            final long e = buf.getLong(off + i + 32);
            final long f = buf.getLong(off + i + 40);
            final long g = buf.getLong(off + i + 48);
            final long h = buf.getLong(off + i + 56);
            LONGS.set(dst, i, a);
            LONGS.set(dst, i + 8, b);
            LONGS.set(dst, i + 16, c);
            LONGS.set(dst, i + 24, d);
            LONGS.set(dst, i + 32, e);
            LONGS.set(dst, i + 40, f);
            LONGS.set(dst, i + 48, g);
            LONGS.set(dst, i + 56, h);
        }
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            LONGS.set(dst, i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            dst[i] = buf.get(off + i);
        }
    }

    static void longLoop16(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        int i = 0;
        for (; i + 128 <= len; i += 128) {
            for (int j = 0; j < 128; j += 8) {
                LONGS.set(dst, i + j, buf.getLong(off + i + j));
            }
        }
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            LONGS.set(dst, i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            dst[i] = buf.get(off + i);
        }
    }

    /** longLoop8 into a ByteBuffer, as read(ByteBuffer) would have to do for a direct destination. */
    static void longLoop8BB(final ByteBuffer buf, final int off, final ByteBuffer out, final int len) {
        int i = 0;
        for (; i + 64 <= len; i += 64) {
            final long a = buf.getLong(off + i);
            final long b = buf.getLong(off + i + 8);
            final long c = buf.getLong(off + i + 16);
            final long d = buf.getLong(off + i + 24);
            final long e = buf.getLong(off + i + 32);
            final long f = buf.getLong(off + i + 40);
            final long g = buf.getLong(off + i + 48);
            final long h = buf.getLong(off + i + 56);
            out.putLong(i, a);
            out.putLong(i + 8, b);
            out.putLong(i + 16, c);
            out.putLong(i + 24, d);
            out.putLong(i + 32, e);
            out.putLong(i + 40, f);
            out.putLong(i + 48, g);
            out.putLong(i + 56, h);
        }
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            out.putLong(i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            out.put(i, buf.get(off + i));
        }
    }

    /** Bulk copies of at most 64 bytes, which aarch64 copies inline in the guarded arraycopy stub. */
    static void bulk64(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        int i = 0;
        for (; i + 64 <= len; i += 64) {
            buf.get(off + i, dst, i, 64);
        }
        if (i < len) {
            buf.get(off + i, dst, i, len - i);
        }
    }

    /** Bulk copies of at most n bytes each. */
    static void chunked(final ByteBuffer buf, final int off, final byte[] dst, final int len, final int n) {
        int i = 0;
        for (; i + n <= len; i += n) {
            buf.get(off + i, dst, i, n);
        }
        if (i < len) {
            buf.get(off + i, dst, i, len - i);
        }
    }

    static void longLoopVH(final ByteBuffer buf, final int off, final byte[] dst, final int len) {
        int i = 0;
        for (; i + Long.BYTES <= len; i += Long.BYTES) {
            LONGS.set(dst, i, buf.getLong(off + i));
        }
        for (; i < len; i++) {
            dst[i] = buf.get(off + i);
        }
    }

    /** A new copier of the given kind, for one thread. */
    /** A lock that no thread holds, for the fence. */
    static final Object FENCE = new Object();

    public static Copy make(final String kind, final Ctx c) throws IOException {
        if (kind.endsWith("F")) {
            // The copy followed by a call of a native method: on the return from native code, HotSpot throws a
            // pending unsafe access error at the call site
            final Copy copy = make(kind.substring(0, kind.length() - 1), c);
            return (off, dst, len) -> {
                copy.copy(off, dst, len);
                if (Thread.holdsLock(FENCE)) {
                    throw new AssertionError();
                }
            };
        }
        final ByteBuffer buf = c.buf;
        if (kind.startsWith("chunk")) {
            // Bulk copies of at most N bytes each, e.g. "chunk64"
            final int n = Integer.parseInt(kind.substring(5));
            return (off, dst, len) -> chunked(buf, (int) off, dst, len, n);
        }
        switch (kind) {
        case "byte1":
            return (off, dst, len) -> dst[0] = buf.get((int) off);
        case "long1":
            return (off, dst, len) -> LONGS.set(dst, 0, buf.getLong((int) off));
        case "bulk":
            return (off, dst, len) -> buf.get((int) off, dst, 0, len);
        case "bulkHeapBuf":
            return (off, dst, len) -> ByteBuffer.wrap(dst).put(0, buf, (int) off, len);
        case "longLoop":
            return (off, dst, len) -> longLoop(buf, (int) off, dst, len);
        case "longLoop4":
            return (off, dst, len) -> longLoop4(buf, (int) off, dst, len);
        case "longLoop8":
            return (off, dst, len) -> longLoop8(buf, (int) off, dst, len);
        case "longLoop16":
            return (off, dst, len) -> longLoop16(buf, (int) off, dst, len);
        case "longLoop8BB": {
            final ByteBuffer[] out = new ByteBuffer[1];
            return (off, dst, len) -> {
                if (out[0] == null || out[0].array() != dst) {
                    out[0] = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN);
                }
                longLoop8BB(buf, (int) off, out[0], len);
            };
        }
        case "longLoop8BBDirect": {
            final ByteBuffer out = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
            return (off, dst, len) -> {
                longLoop8BB(buf, (int) off, out, len);
                dst[len - 1] = out.get(len - 1);
            };
        }
        case "bulk64":
            return (off, dst, len) -> bulk64(buf, (int) off, dst, len);
        case "longLoopVH":
            return (off, dst, len) -> longLoopVH(buf, (int) off, dst, len);
        case "segCopy":
            return c.arena.segCopy();
        case "segLongLoop":
            return c.arena.segLongLoop();
        case "sizeCheckBulk":
            return (off, dst, len) -> {
                if (c.ch.size() < off + len) {
                    throw new EOFException("File is shorter than " + (off + len));
                }
                buf.get((int) off, dst, 0, len);
            };
        case "channel":
            return (off, dst, len) -> readFully(c.ch, ByteBuffer.wrap(dst, 0, len), off);
        case "channelDirect": {
            final ByteBuffer direct = ByteBuffer.allocateDirect(1 << 20);
            return (off, dst, len) -> {
                direct.clear().limit(len);
                readFully(c.ch, direct, off);
                direct.get(0, dst, 0, len);
            };
        }
        case "channelPerThread": {
            final FileChannel own = FileChannel.open(c.file, StandardOpenOption.READ);
            return (off, dst, len) -> readFully(own, ByteBuffer.wrap(dst, 0, len), off);
        }
        case "raf": {
            final RandomAccessFile raf = new RandomAccessFile(c.file.toFile(), "r");
            return (off, dst, len) -> {
                raf.seek(off);
                raf.readFully(dst, 0, len);
            };
        }
        case "heap":
            return (off, dst, len) -> System.arraycopy(c.heap, (int) off, dst, 0, len);
        default:
            throw new IllegalArgumentException(kind);
        }
    }
}
