import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;

/**
 * Times reading 32 MB of a cached 256 MB file once, in consecutive reads of one size, through a mapping made just
 * before (so that every page is touched for the first time, as in a scan), by one kind of copy, on 1 thread and on
 * every core (each thread reading its own part of the 32 MB). Each round maps a different 32 MB of the file. Prints
 * one RESULT line per size and thread count: ns per read per thread, the median of 7 rounds after 1 untimed round.
 * Needs JDK 22 or later, since it maps the file into an arena so that each round can unmap it.
 *
 * <p>
 * Args: dir kind
 */
public class ColdBench {
    static final long FILE_SIZE = 256L << 20;
    static final long REGION = 32L << 20;
    static final int[] SIZES = { 256, 1024, 4096, 16384, 32768, 65536, 262144, 1048576 };
    static volatile long sink;

    public static void main(final String[] args) throws Exception {
        final Path dir = Path.of(args[0]);
        final String kind = args[1];
        final Path file = dir.resolve("cold.bin");
        if (!Files.exists(file) || Files.size(file) != FILE_SIZE) {
            final byte[] chunk = new byte[1 << 20];
            final Random rand = new Random(1);
            try (var out = Files.newOutputStream(file)) {
                for (long n = 0; n < FILE_SIZE; n += chunk.length) {
                    rand.nextBytes(chunk);
                    out.write(chunk);
                }
            }
        }
        final Common.Ctx warm = new Common.Ctx(file, "arena");
        final byte[] warmBuf = new byte[1 << 20];
        final Common.Copy channel = Common.make("channel", warm);
        for (long off = 0; off < FILE_SIZE; off += warmBuf.length) {
            channel.copy(off, warmBuf, warmBuf.length);
        }
        warm.close();

        final int cores = Runtime.getRuntime().availableProcessors();
        final int[] threadCounts = cores > 1 ? new int[] { 1, cores } : new int[] { 1 };
        int round = 0;
        for (final int size : SIZES) {
            for (final int threads : threadCounts) {
                final double[] times = new double[8];
                for (int r = 0; r < times.length; r++) {
                    times[r] = run(file, kind, size, threads, (round++ % (FILE_SIZE / REGION)) * REGION);
                }
                final double[] timed = Arrays.copyOfRange(times, 1, times.length);
                Arrays.sort(timed);
                System.out.printf("RESULT %s arena %d %d 0 %.1f%n", kind, size, threads, timed[timed.length / 2]);
            }
        }
    }

    /** Maps the file, reads REGION bytes at regionStart, unmaps it, and returns ns per read per thread. */
    static double run(final Path file, final String kind, final int size, final int threads, final long regionStart)
            throws Exception {
        final Common.Ctx ctx = new Common.Ctx(file, "arena");
        try {
            final List<Common.Copy> copies = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                copies.add(Common.make(kind, ctx));
            }
            final long perThread = REGION / threads / size * size;
            final Thread[] ts = new Thread[threads];
            final Throwable[] failure = new Throwable[1];
            final CyclicBarrier go = new CyclicBarrier(threads + 1);
            for (int t = 0; t < threads; t++) {
                final Common.Copy copy = copies.get(t);
                final long start = regionStart + t * perThread;
                ts[t] = new Thread(() -> {
                    try {
                        final byte[] dst = new byte[size];
                        go.await();
                        for (long off = start; off < start + perThread; off += size) {
                            copy.copy(off, dst, size);
                            sink += dst[size - 1];
                        }
                    } catch (final Throwable e) {
                        failure[0] = e;
                    }
                });
                ts[t].start();
            }
            go.await();
            final long begin = System.nanoTime();
            for (final Thread t : ts) {
                t.join();
            }
            final double elapsed = System.nanoTime() - begin;
            if (failure[0] != null) {
                throw new RuntimeException(failure[0]);
            }
            return elapsed * threads / (perThread / size * threads);
        } finally {
            ctx.close();
        }
    }
}
