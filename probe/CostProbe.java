import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.perf.BenchmarkFixtures;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** Times the phases of reading a stored jarfile and a directory, single-threaded, to find where time goes. */
public class CostProbe {
    static final int ROUNDS = 40, SKIP = 15;
    static final Map<String, List<Double>> times = new LinkedHashMap<>();
    static long sink;

    interface Body { void run(long[] t) throws Exception; }

    /** Runs body ROUNDS times; body fills t[0..k] with nanoTime stamps; records each phase. */
    static void time(final String label, final String[] phases, final Body body) throws Exception {
        final List<double[]> rows = new ArrayList<>();
        for (int r = 0; r < ROUNDS; r++) {
            final long[] t = new long[phases.length + 1];
            body.run(t);
            if (r >= SKIP) {
                final double[] d = new double[phases.length];
                for (int i = 0; i < phases.length; i++) d[i] = (t[i + 1] - t[i]) / 1e6;
                rows.add(d);
            }
        }
        final StringBuilder sb = new StringBuilder(String.format("  %-34s", label));
        double total = 0;
        for (int i = 0; i < phases.length; i++) {
            final int fi = i;
            final double[] col = rows.stream().mapToDouble(d -> d[fi]).sorted().toArray();
            final double med = col[col.length / 2];
            total += med;
            sb.append(String.format("  %s %8.3f", phases[i], med));
        }
        sb.append(String.format("  | total %8.3f ms", total));
        System.out.println(sb);
    }

    static void readWhole(final ByteBuffer buf, final byte[] dst) {
        for (int pos = 0; pos < buf.limit(); pos += dst.length) {
            buf.get(pos, dst, 0, Math.min(dst.length, buf.limit() - pos));
        }
        sink += dst[0];
    }

    public static void main(final String[] args) throws Exception {
        System.out.println(System.getProperty("os.name") + " " + System.getProperty("java.version") + ", "
                + Runtime.getRuntime().availableProcessors() + " CPUs. Median ms of " + (ROUNDS - SKIP) + " rounds");
        for (final Content content : Content.values()) {
            final Path jar = BenchmarkFixtures.path(content, Layout.STORED);
            final List<String> names = BenchmarkFixtures.entryNames(content);
            System.out.printf("%n%s stored: %d entries, %.1f MB file%n", content, names.size(),
                    Files.size(jar) / 1e6);
            final String[] p3 = { "open", "read", "close" };
            time("cgvfs", p3, t -> {
                t[0] = System.nanoTime();
                final var vfs = new Vfs();
                final var root = vfs.open(jar.toString());
                t[1] = System.nanoTime();
                for (final var n : names) sink += root.getEntry(n).load().length;
                t[2] = System.nanoTime();
                vfs.close();
                t[3] = System.nanoTime();
            });
            time("JDK ZipFile", p3, t -> {
                t[0] = System.nanoTime();
                final var z = new ZipFile(jar.toFile());
                t[1] = System.nanoTime();
                for (final var n : names) {
                    try (var in = z.getInputStream(z.getEntry(n))) { sink += in.readAllBytes().length; }
                }
                t[2] = System.nanoTime();
                z.close();
                t[3] = System.nanoTime();
            });
            final byte[] chunk = new byte[64 * 1024];
            time("raw map, shared arena", p3, t -> {
                t[0] = System.nanoTime();
                final var ch = FileChannel.open(jar, StandardOpenOption.READ);
                final var arena = Arena.ofShared();
                final var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena).asByteBuffer();
                t[1] = System.nanoTime();
                readWhole(buf, chunk);
                t[2] = System.nanoTime();
                arena.close();
                ch.close();
                t[3] = System.nanoTime();
            });
            time("raw map, confined arena", p3, t -> {
                t[0] = System.nanoTime();
                final var ch = FileChannel.open(jar, StandardOpenOption.READ);
                final var arena = Arena.ofConfined();
                final var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena).asByteBuffer();
                t[1] = System.nanoTime();
                readWhole(buf, chunk);
                t[2] = System.nanoTime();
                arena.close();
                ch.close();
                t[3] = System.nanoTime();
            });
            time("raw map, GC-unmapped buffer", p3, t -> {
                t[0] = System.nanoTime();
                final var ch = FileChannel.open(jar, StandardOpenOption.READ);
                final var buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                t[1] = System.nanoTime();
                readWhole(buf, chunk);
                t[2] = System.nanoTime();
                ch.close();
                t[3] = System.nanoTime();
            });
            time("raw channel, 64 KB positional", p3, t -> {
                t[0] = System.nanoTime();
                final var ch = FileChannel.open(jar, StandardOpenOption.READ);
                final var bb = ByteBuffer.wrap(chunk);
                t[1] = System.nanoTime();
                final long size = ch.size();
                for (long pos = 0; pos < size; pos += chunk.length) {
                    bb.clear();
                    ch.read(bb, pos);
                }
                t[2] = System.nanoTime();
                ch.close();
                t[3] = System.nanoTime();
            });
        }
        for (final Content content : Content.values()) {
            final Path dir = BenchmarkFixtures.path(content, Layout.DIR);
            final List<String> names = BenchmarkFixtures.entryNames(content);
            final List<Path> dirs;
            try (Stream<Path> w = Files.walk(dir)) { dirs = w.filter(Files::isDirectory).toList(); }
            System.out.printf("%n%s dir: %d files in %d directories (ms per round)%n", content, names.size(),
                    dirs.size());
            final String[] p1 = { "all" };
            time("stat every file", p1, t -> {
                t[0] = System.nanoTime();
                for (final var n : names) sink += Files.readAttributes(dir.resolve(n), BasicFileAttributes.class).size();
                t[1] = System.nanoTime();
            });
            time("list every directory", p1, t -> {
                t[0] = System.nanoTime();
                for (final var d : dirs) {
                    try (var ds = Files.newDirectoryStream(d)) { for (final var x : ds) sink += x.hashCode(); }
                }
                t[1] = System.nanoTime();
            });
            time("JDK Files.readAllBytes", p1, t -> {
                t[0] = System.nanoTime();
                for (final var n : names) sink += Files.readAllBytes(dir.resolve(n)).length;
                t[1] = System.nanoTime();
            });
            time("JDK channel open+size+read", p1, t -> {
                t[0] = System.nanoTime();
                for (final var n : names) {
                    try (var ch = FileChannel.open(dir.resolve(n), StandardOpenOption.READ)) {
                        final var bb = ByteBuffer.allocate((int) ch.size());
                        while (bb.hasRemaining() && ch.read(bb, bb.position()) >= 0) { }
                        sink += bb.capacity();
                    }
                }
                t[1] = System.nanoTime();
            });
            final String[] p4 = { "open", "getEntry", "load", "close" };
            time("cgvfs getEntry, then load", p4, t -> {
                t[0] = System.nanoTime();
                final var vfs = new Vfs();
                final var root = vfs.open(dir.toString());
                t[1] = System.nanoTime();
                final var entries = new ArrayList<io.github.classgraph.vfs.VfsEntry>(names.size());
                for (final var n : names) entries.add(root.getEntry(n));
                t[2] = System.nanoTime();
                for (final var e : entries) sink += e.load().length;
                t[3] = System.nanoTime();
                vfs.close();
                t[4] = System.nanoTime();
            });
            final String[] p4w = { "open", "walk", "load", "close" };
            time("cgvfs walk, then load", p4w, t -> {
                t[0] = System.nanoTime();
                final var vfs = new Vfs();
                final var root = vfs.open(dir.toString());
                t[1] = System.nanoTime();
                final var entries = root.getEntries();
                t[2] = System.nanoTime();
                for (final var e : entries) sink += e.load().length;
                t[3] = System.nanoTime();
                vfs.close();
                t[4] = System.nanoTime();
            });
        }
        System.out.println("\n(sink " + sink + ")");
    }
}
