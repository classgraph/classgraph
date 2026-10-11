import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * What does the operating system let another program do to a file while this process holds it open or mapped? For
 * each way of holding a file, this tries to delete, rename and overwrite a fresh copy of it, and prints the
 * outcome. Windows refuses some of these, and which ones depends both on how the file was opened and on the version
 * of Windows; Linux and macOS allow all of them.
 *
 * ClassGraph maps a file through FileChannel#open, and unmaps it by closing the arena it was mapped in, so the
 * "FileChannel#open" and "arena" rows are the ones that describe ClassGraph. The others are for comparison: the
 * JDK's own ZipFile, and anything that maps a file through a RandomAccessFile, hold it the way those rows show.
 *
 * This is what the lock tables of the memory mapping benchmark wiki page are measured with. Needs JDK 22 or newer.
 * Always exits 0; the answer is in the printed output.
 *
 * Run with: java MapLockProbe.java
 */
public class MapLockProbe {
    /** Everything that holds a file, kept reachable so that nothing is closed or unmapped by the collector. */
    private static final List<Object> HELD = new ArrayList<>();

    /** A way of holding a file. */
    private interface Holder {
        /**
         * Hold the file.
         *
         * @param file
         *            the file to hold
         * @throws Exception
         *             if the file could not be opened or mapped
         */
        void hold(Path file) throws Exception;
    }

    /** An operation that another program might try on the file. */
    private interface Operation {
        /**
         * Try the operation.
         *
         * @param file
         *            the file to try it on
         * @throws Exception
         *             if the operation was refused
         */
        void apply(Path file) throws Exception;
    }

    /**
     * Map the whole of a file, keeping the mapping reachable.
     *
     * @param channel
     *            the channel to map the file through
     * @param file
     *            the file
     * @throws Exception
     *             if the file could not be mapped
     */
    private static void map(final FileChannel channel, final Path file) throws Exception {
        HELD.add(channel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file)));
    }

    public static void main(final String[] args) throws Exception {
        System.out.printf("os=%s %s, java=%s%n", System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("java.version"));

        final String[] holderNames = { //
                "FileChannel#open, mapped, channel open", //
                "FileChannel#open, mapped, channel closed", //
                "FileChannel#open, not mapped", //
                "mapped in an arena, arena closed", //
                "RandomAccessFile, mapped, file open", //
                "RandomAccessFile, mapped, file closed", //
                "RandomAccessFile, not mapped", //
                "FileInputStream", //
                "ZipFile", //
        };
        final Holder[] holders = { //
                file -> {
                    final var channel = FileChannel.open(file, StandardOpenOption.READ);
                    HELD.add(channel);
                    map(channel, file);
                }, //
                file -> {
                    try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                        map(channel, file);
                    }
                }, //
                file -> HELD.add(FileChannel.open(file, StandardOpenOption.READ)), //
                file -> {
                    try (var channel = FileChannel.open(file, StandardOpenOption.READ);
                            var arena = Arena.ofShared()) {
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file), arena)
                                .get(ValueLayout.JAVA_BYTE, 0);
                    }
                }, //
                file -> {
                    final var randomAccessFile = new RandomAccessFile(file.toFile(), "r");
                    HELD.add(randomAccessFile);
                    map(randomAccessFile.getChannel(), file);
                }, //
                file -> {
                    try (var randomAccessFile = new RandomAccessFile(file.toFile(), "r")) {
                        map(randomAccessFile.getChannel(), file);
                    }
                }, //
                file -> HELD.add(new RandomAccessFile(file.toFile(), "r")), //
                file -> HELD.add(new FileInputStream(file.toFile())), //
                file -> HELD.add(new ZipFile(file.toFile())), //
        };
        final String[] operationNames = { "delete", "rename", "overwrite" };
        final Operation[] operations = { //
                Files::delete, //
                file -> Files.move(file, file.resolveSibling(file.getFileName() + ".renamed")), //
                file -> {
                    try (var out = new FileOutputStream(file.toFile())) {
                        out.write(1);
                    }
                }, //
        };

        final Path dir = Files.createTempDirectory("maplockprobe");
        for (int h = 0; h < holders.length; h++) {
            System.out.println(holderNames[h] + ":");
            for (int o = 0; o < operations.length; o++) {
                final Path file = dir.resolve("file-" + h + "-" + o + ".zip");
                writeZip(file);
                holders[h].hold(file);
                String outcome;
                try {
                    operations[o].apply(file);
                    outcome = "OK";
                } catch (final Exception e) {
                    outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                System.out.printf("  %-10s %s%n", operationNames[o], outcome);
            }
        }

        // Close everything that was held open. A mapping made without an arena is only released once the collector
        // has freed its buffer, so drop every reference, and ask for a collection until the files that were still
        // mapped can be deleted
        for (final Object held : HELD) {
            if (held instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        HELD.clear();
        boolean deleted = deleteAll(dir);
        for (int attempt = 0; attempt < 10 && !deleted; attempt++) {
            System.gc();
            Thread.sleep(100);
            deleted = deleteAll(dir);
        }
        if (!deleted) {
            System.out.println("could not delete " + dir);
        }
    }

    /**
     * Delete a directory and every file in it.
     *
     * @param dir
     *            the directory
     * @return true if the directory was deleted
     * @throws Exception
     *             if the directory could not be listed
     */
    private static boolean deleteAll(final Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                try {
                    Files.delete(file);
                } catch (final Exception e) {
                    // Still held: the directory delete below fails too, and the caller tries again
                }
            }
        }
        try {
            Files.delete(dir);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Write a zipfile holding one entry, so that every way of holding a file, including {@link ZipFile}, can open
     * it.
     *
     * @param file
     *            the file to write
     * @throws Exception
     *             if the file could not be written
     */
    private static void writeZip(final Path file) throws Exception {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(file))) {
            zipOut.putNextEntry(new ZipEntry("entry.txt"));
            zipOut.write(new byte[4096]);
            zipOut.closeEntry();
        }
    }
}
