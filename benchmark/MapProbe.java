import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probe: on this OS and JDK, does a live memory mapping block deletion of the mapped file after the FileChannel and
 * RandomAccessFile have been closed? If so, does dropping every reference and calling System.gc() release it, and
 * after how many rounds? Also: does an alias (a duplicate, which is what a reader holds) keep the mapping alive?
 *
 * This is what the "Releasing a mapping differs below JDK 22" section of the memory mapping benchmark wiki page is
 * measured with. T1 and T5 are why a mapping has to be released at all: Windows refuses to delete, rename or
 * overwrite a file while it is mapped. T8 on Linux, and T7 on Windows, are why it is not released by dropping the
 * last reference and asking for a collection: over enough rounds that leaves a file still mapped when the request
 * to collect returns, which on Windows means still locked. So a mapping is unmapped explicitly instead, and the
 * collector is only the fallback.
 *
 * Always exits 0; the answer is in the printed output.
 *
 * Run with: java MapProbe.java
 *
 * Run again with -XX:+DisableExplicitGC to see what happens when System.gc() does nothing.
 */
public class MapProbe {

    /**
     * References are held in static fields, not locals: a local whose last read has already happened is not a GC
     * root, so the JIT could collect the buffer before the code says to drop it, and the probe would report "not
     * blocked" for the wrong reason.
     */
    static MappedByteBuffer heldMapping;
    static ByteBuffer heldDuplicate;

    static File makeFile(final String name, final int len) throws IOException {
        final File f = File.createTempFile(name, ".bin");
        final FileOutputStream out = new FileOutputStream(f);
        try {
            final byte[] b = new byte[len];
            for (int i = 0; i < len; i++) {
                b[i] = (byte) (i * 31 + 7);
            }
            out.write(b);
        } finally {
            out.close();
        }
        return f;
    }

    /** "DELETED", "ALREADY GONE", or "BLOCKED (...)". */
    static String tryDelete(final File f) {
        if (!f.exists()) {
            return "ALREADY GONE";
        }
        try {
            Files.delete(f.toPath());
            return "DELETED";
        } catch (final Exception e) {
            return "BLOCKED (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Delete without reporting why it failed. */
    static boolean deleteQuietly(final File f) {
        try {
            Files.delete(f.toPath());
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * gc, sleep, retry delete. Returns the round the delete succeeded on, or -1 if it never did within maxRounds,
     * or 0 if the file was already gone before the first round.
     */
    static int gcAndRetryDelete(final File f, final int maxRounds) throws InterruptedException {
        if (!f.exists()) {
            return 0;
        }
        for (int i = 1; i <= maxRounds; i++) {
            System.gc();
            Thread.sleep(50);
            try {
                Files.delete(f.toPath());
                return i;
            } catch (final Exception e) {
                // Still blocked; retry
            }
        }
        return -1;
    }

    static String rounds(final int n, final int maxRounds) {
        if (n == 0) {
            return "n/a (file was already gone -- this OS unlinks mapped files)";
        } else if (n < 0) {
            return "NEVER (still blocked after " + maxRounds + " rounds x 50ms)";
        } else {
            return n + " round" + (n == 1 ? "" : "s");
        }
    }

    public static void main(final String[] args) throws Exception {
        final boolean explicitGcDisabled = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().contains("-XX:+DisableExplicitGC");
        System.out.println("### os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch")
                + " java=" + System.getProperty("java.version") + " DisableExplicitGC=" + explicitGcDisabled);

        // ---- T1: is the mapped file deletable while the mapping is live but the channel and raf are closed? ----
        final File f1 = makeFile("mapprobe1", 1 << 20);
        final RandomAccessFile raf1 = new RandomAccessFile(f1, "r");
        final FileChannel ch1 = raf1.getChannel();
        heldMapping = ch1.map(FileChannel.MapMode.READ_ONLY, 0, f1.length());
        System.out.println("T1 read through mapping before close: byte[0]=" + heldMapping.get(0));
        ch1.close();
        raf1.close();
        System.out.println("T1 read through mapping AFTER channel+raf close: byte[1]=" + heldMapping.get(1));
        final String t1 = tryDelete(f1);
        System.out.println("T1 delete while the mapping is still live and held: " + t1);

        // ---- T2: drop the only reference to the mapping, gc, retry the delete ----
        heldMapping = null;
        final int r2 = gcAndRetryDelete(f1, 40);
        System.out.println("T2 delete after dropping the mapping ref: " + rounds(r2, 40));
        System.out.println("T2 file still exists = " + f1.exists());

        // ---- T3: does a duplicate (the alias a reader holds) keep the mapping -- and the file lock -- alive? ----
        final File f3 = makeFile("mapprobe3", 1 << 20);
        final RandomAccessFile raf3 = new RandomAccessFile(f3, "r");
        final FileChannel ch3 = raf3.getChannel();
        heldMapping = ch3.map(FileChannel.MapMode.READ_ONLY, 0, f3.length());
        heldDuplicate = heldMapping.duplicate();
        heldMapping = null; // Only the duplicate is reachable now
        ch3.close();
        raf3.close();
        final int r3 = gcAndRetryDelete(f3, 10);
        System.out.println("T3 delete while only a duplicate is held: " + rounds(r3, 10));
        System.out.println("T3 duplicate STILL READABLE after those gcs: byte[0]=" + heldDuplicate.get(0)
                + " (if this prints, the mapping survived while an alias was reachable)");
        heldDuplicate = null;
        final int r3b = gcAndRetryDelete(f3, 40);
        System.out.println("T3b delete after dropping the duplicate too: " + rounds(r3b, 40));

        // ---- T4: cost of map+touch vs read-into-heap+touch, 8MB x5 ----
        final File f4 = makeFile("mapprobe4", 8 << 20);
        long sum = 0;
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 5; rep++) {
            final RandomAccessFile r = new RandomAccessFile(f4, "r");
            final FileChannel c = r.getChannel();
            final MappedByteBuffer m = c.map(FileChannel.MapMode.READ_ONLY, 0, f4.length());
            for (int i = 0; i + 4 < m.capacity(); i += 4093) {
                sum += m.get(i);
            }
            c.close();
            r.close();
        }
        final long mapNanos = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int rep = 0; rep < 5; rep++) {
            final RandomAccessFile r = new RandomAccessFile(f4, "r");
            final FileChannel c = r.getChannel();
            final ByteBuffer b = ByteBuffer.allocate((int) f4.length());
            while (b.hasRemaining() && c.read(b) >= 0) {
                // Read to the end
            }
            b.flip();
            for (int i = 0; i + 4 < b.capacity(); i += 4093) {
                sum += b.get(i);
            }
            c.close();
            r.close();
        }
        final long readNanos = System.nanoTime() - t0;
        System.out.println("T4 8MB x5: map+touch=" + (mapNanos / 1000000) + "ms  readIntoHeap+touch="
                + (readNanos / 1000000) + "ms  (checksum " + sum + ")");
        System.out.println("T4 delete of the benchmark file after gc: " + rounds(gcAndRetryDelete(f4, 40), 40));

        // ---- T5: can the mapped file be renamed / overwritten while the mapping is live? ----
        final File f5 = makeFile("mapprobe5", 1 << 20);
        final RandomAccessFile raf5 = new RandomAccessFile(f5, "r");
        final FileChannel ch5 = raf5.getChannel();
        heldMapping = ch5.map(FileChannel.MapMode.READ_ONLY, 0, f5.length());
        ch5.close();
        raf5.close();
        final File f5b = new File(f5.getParentFile(), f5.getName() + ".renamed");
        System.out.println("T5 rename while the mapping is live: " + (f5.renameTo(f5b) ? "OK" : "REFUSED"));
        String writeResult;
        try {
            final FileOutputStream o = new FileOutputStream(f5.exists() ? f5 : f5b);
            o.write(1);
            o.close();
            writeResult = "OK";
        } catch (final Exception e) {
            writeResult = "REFUSED (" + e.getClass().getSimpleName() + ")";
        }
        System.out.println("T5 overwrite while the mapping is live: " + writeResult);
        heldMapping = null;
        gcAndRetryDelete(f5, 20);
        gcAndRetryDelete(f5b, 20);

        // ---- T6: 50 mapped files released at once -- how many gc rounds clear them all? ----
        final int n = 50;
        final File[] files = new File[n];
        final MappedByteBuffer[] mappings = new MappedByteBuffer[n];
        for (int i = 0; i < n; i++) {
            files[i] = makeFile("mapprobe6_" + i, 1 << 16);
            final RandomAccessFile r = new RandomAccessFile(files[i], "r");
            final FileChannel c = r.getChannel();
            mappings[i] = c.map(FileChannel.MapMode.READ_ONLY, 0, files[i].length());
            c.close();
            r.close();
        }
        int blockedBeforeGc = 0;
        for (int i = 0; i < n; i++) {
            if (files[i].exists() && !deleteQuietly(files[i])) {
                blockedBeforeGc++;
            }
        }
        System.out.println("T6 of " + n + " mapped files, still undeletable before any gc: " + blockedBeforeGc);
        for (int i = 0; i < n; i++) {
            mappings[i] = null;
        }
        int roundsUsed = -1;
        for (int round = 1; round <= 20; round++) {
            System.gc();
            Thread.sleep(50);
            int remaining = 0;
            for (int i = 0; i < n; i++) {
                if (files[i].exists() && !deleteQuietly(files[i])) {
                    remaining++;
                }
            }
            if (remaining == 0) {
                roundsUsed = round;
                break;
            }
        }
        System.out.println("T6 gc rounds until ALL " + n + " were deletable: "
                + (roundsUsed < 0 ? "NEVER within 20 rounds" : String.valueOf(roundsUsed)));

        // ---- T7: is a sleep needed after System.gc(), or does gc-then-retry-immediately suffice? ----
        // This decides whether the temp-file delete retry has to sleep inside close()
        for (final int sleepMillis : new int[] { 0, 1, 10 }) {
            // This only bites on Windows: every other OS unlinks a file that is still mapped, so the delete here
            // succeeds whether or not the collector got to the mapping. T8 asks the question directly instead
            final int reps = 300;
            int worstRound = 0;
            int failures = 0;
            for (int rep = 0; rep < reps; rep++) {
                final File f = makeFile("mapprobe7", 1 << 16);
                final RandomAccessFile r = new RandomAccessFile(f, "r");
                final FileChannel c = r.getChannel();
                heldMapping = c.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
                c.close();
                r.close();
                heldMapping = null;
                int round = -1;
                for (int i = 1; i <= 5; i++) {
                    System.gc();
                    if (sleepMillis > 0) {
                        Thread.sleep(sleepMillis);
                    }
                    if (deleteQuietly(f)) {
                        round = i;
                        break;
                    }
                }
                if (round < 0) {
                    failures++;
                    deleteQuietly(f);
                } else if (round > worstRound) {
                    worstRound = round;
                }
            }
            System.out.println("T7 sleep=" + sleepMillis + "ms: over " + reps
                    + " map/drop/delete cycles, worst round = " + worstRound + ", never-deleted = " + failures);
        }

        // ---- T8: does dropping every reference and asking for a collection actually unmap the file? ----
        // T2, T6 and T7 all ask by trying to delete the file, which on every OS but Windows succeeds whether or
        // not the file is still mapped. Linux can be asked directly, through /proc/self/maps, so ask it there:
        // this is the measurement that says whether a mapping can be released by dropping the last reference to
        // it, which is what a JDK below 22 would have to rely on if it did not unmap explicitly.
        //
        // The file is unlinked while it is still mapped, which is what a scan does with a temporary file it
        // extracted, and is also what makes a straggler visible: an unlinked file that is still mapped stays in
        // /proc/self/maps with " (deleted)" appended, whereas one left on disk is released promptly enough in this
        // loop that the check never catches it.
        if (Files.exists(PROC_SELF_MAPS)) {
            for (final boolean waitForReferenceProcessing : new boolean[] { false, true }) {
                final int rounds = 300;
                final int filesPerRound = 8;
                int stillMappedWhenGcReturned = 0;
                int stillMappedAfterMoreCollections = 0;
                for (int round = 0; round < rounds; round++) {
                    final String[] names = new String[filesPerRound];
                    heldMappings = new MappedByteBuffer[filesPerRound];
                    for (int i = 0; i < filesPerRound; i++) {
                        final File f = makeFile("mapprobe8_" + round + "_" + i + "_", 1 << 16);
                        names[i] = f.getName();
                        final RandomAccessFile r = new RandomAccessFile(f, "r");
                        final FileChannel c = r.getChannel();
                        heldMappings[i] = c.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
                        c.close();
                        r.close();
                        deleteQuietly(f);
                    }
                    // Drop every reference to every mapping, then ask for them to be unmapped
                    heldMappings = null;
                    System.gc();
                    if (waitForReferenceProcessing) {
                        awaitReferenceProcessing();
                    }
                    if (anyStillMapped(names)) {
                        stillMappedWhenGcReturned++;
                        // Give the collector every chance, to tell a straggler from a mapping it never releases
                        for (int retry = 0; retry < 2; retry++) {
                            System.gc();
                            Thread.sleep(50);
                        }
                        if (anyStillMapped(names)) {
                            stillMappedAfterMoreCollections++;
                        }
                    }
                }
                System.out.println("T8 "
                        + (waitForReferenceProcessing ? "gc + wait for reference processing" : "gc only")
                        + ": over " + rounds + " rounds of mapping " + filesPerRound
                        + " files and dropping every reference, a file was still mapped when the request to "
                        + "collect returned in " + stillMappedWhenGcReturned
                        + " rounds, and after two more collections and 100ms in " + stillMappedAfterMoreCollections
                        + " rounds");
            }
        } else {
            System.out.println("T8 skipped: only Linux can say which files are mapped, through /proc/self/maps");
        }

        System.out.println("### DONE");
    }

    /** The file through which Linux says which files this process has memory-mapped. */
    private static final Path PROC_SELF_MAPS = Path.of("/proc/self/maps");

    /** Mappings held in a static field, for the same reason as {@link #heldMapping}. */
    private static MappedByteBuffer[] heldMappings;

    /**
     * Whether any of the named files is still memory-mapped by this JVM, according to /proc/self/maps. The name is
     * looked for anywhere in the line rather than only at the end, since a file that has been unlinked while it is
     * still mapped is listed with " (deleted)" appended.
     */
    private static boolean anyStillMapped(final String[] names) throws IOException {
        final var maps = Files.readAllLines(PROC_SELF_MAPS);
        for (final String name : names) {
            for (final String line : maps) {
                if (line.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wait for the references that the collection found to be processed, the same way the library does: a phantom
     * reference to an object that the same collection finds unreachable is enqueued while those references are
     * processed, so waiting for it to be enqueued waits for most of that processing.
     */
    private static void awaitReferenceProcessing() throws InterruptedException {
        final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        final PhantomReference<Object> canary = new PhantomReference<>(new Object(), collected);
        System.gc();
        collected.remove(100);
        // Keep the canary reachable until it has been waited for -- a phantom reference that has itself become
        // unreachable is never enqueued
        canary.clear();
    }
}
