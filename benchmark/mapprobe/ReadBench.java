import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Times random reads of a 128 MB file that is in the page cache, by one kind of copy, for a range of read sizes, on
 * 1 thread and on every core. Prints one RESULT line per size and thread count: total MB/s, and ns per read per
 * thread (the median of 3 rounds of 0.4 s, after 0.5 s of warmup).
 *
 * <p>
 * Args: dir map(legacy|arena) kind
 */
public class ReadBench {
    static final long FILE_SIZE = 128L << 20;
    static final int[] SIZES = { 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576 };
    static volatile long sink;

    public static void main(final String[] args) throws Exception {
        final Path dir = Path.of(args[0]);
        final String map = args[1], kind = args[2];
        final Path file = dir.resolve("bench.bin");
        if (!Files.exists(file) || Files.size(file) != FILE_SIZE) {
            final byte[] content = new byte[(int) FILE_SIZE];
            new Random(1).nextBytes(content);
            Files.write(file, content);
        }
        final Common.Ctx ctx = new Common.Ctx(file, map);
        if (kind.equals("heap")) {
            System.out.printf("LOAD %s %.1f ms for 128 MB%n", kind, ctx.loadHeap());
        }
        // Put the whole file in the page cache
        final byte[] warmBuf = new byte[1 << 20];
        final Common.Copy channel = Common.make("channel", ctx);
        for (long off = 0; off < FILE_SIZE; off += warmBuf.length) {
            channel.copy(off, warmBuf, warmBuf.length);
        }

        final int cores = Runtime.getRuntime().availableProcessors();
        final int[] threadCounts = cores > 1 ? new int[] { 1, cores } : new int[] { 1 };
        final List<Common.Copy> copies = new ArrayList<>();
        for (int t = 0; t < cores; t++) {
            copies.add(Common.make(kind, ctx));
        }
        for (final int size : SIZES) {
            for (final int threads : threadCounts) {
                run(copies, size, threads, 0.5);
                final double[][] rounds = new double[3][];
                for (int r = 0; r < rounds.length; r++) {
                    rounds[r] = run(copies, size, threads, 0.4);
                }
                Arrays.sort(rounds, (a, b) -> Double.compare(a[0], b[0]));
                final double[] median = rounds[1];
                System.out.printf("RESULT %s %s %d %d %.1f %.1f%n", kind, map, size, threads, median[0], median[1]);
            }
        }
    }

    /** Returns { total MB/s, ns per read per thread }. */
    static double[] run(final List<Common.Copy> copies, final int size, final int threads, final double seconds)
            throws Exception {
        final CyclicBarrier start = new CyclicBarrier(threads + 1);
        final AtomicLong reads = new AtomicLong();
        final long[] endNanos = new long[1];
        final Thread[] ts = new Thread[threads];
        final Throwable[] failure = new Throwable[1];
        for (int t = 0; t < threads; t++) {
            final Common.Copy copy = copies.get(t);
            final long seed = 0x9E3779B97F4A7C15L * (t + 1);
            ts[t] = new Thread(() -> {
                try {
                    final byte[] dst = new byte[size];
                    long x = seed;
                    long n = 0;
                    start.await();
                    final long end = endNanos[0];
                    do {
                        for (int i = 0; i < 16; i++) {
                            x ^= x << 13;
                            x ^= x >>> 7;
                            x ^= x << 17;
                            final long off = Math.min((x & (FILE_SIZE - 1)), FILE_SIZE - size) & ~63L;
                            copy.copy(off, dst, size);
                            n++;
                        }
                        sink += dst[size - 1];
                    } while (System.nanoTime() < end);
                    reads.addAndGet(n);
                } catch (final Throwable e) {
                    failure[0] = e;
                }
            });
            ts[t].start();
        }
        final long begin = System.nanoTime();
        endNanos[0] = begin + (long) (seconds * 1e9);
        start.await();
        for (final Thread t : ts) {
            t.join();
        }
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
        final double elapsed = (System.nanoTime() - begin) / 1e9;
        final long n = reads.get();
        return new double[] { n * (double) size / elapsed / 1e6, elapsed * threads * 1e9 / n };
    }
}
