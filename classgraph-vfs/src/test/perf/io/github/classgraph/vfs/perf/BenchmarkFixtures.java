/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.perf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The archives the benchmarks read, generated on first use into the directory named by the system property
 * {@code cgvfs.bench.dir} (by default {@code cgvfs-bench} under {@code java.io.tmpdir}), and reused after that.
 * Everything is generated from fixed seeds, so the same JDK always generates the same bytes.
 *
 * <p>
 * Each of the three {@link Content} types is laid out in each of the five {@link Layout}s, so that the same entries
 * can be read from a directory, a jarfile and a nested jarfile.
 */
public final class BenchmarkFixtures {
    /** The name of the nested jarfile inside the outer jarfile of the two nested layouts. */
    public static final String INNER_JAR = "lib/inner.jar";

    /** What the entries hold. */
    public enum Content {
        /**
         * 2000 classfiles of the running JDK's {@code java.base} module, every n-th one in name order, so that they
         * come from most of its packages: small entries that deflate to about half their size, which is what a
         * classpath scan reads. Their median size is about 2KB, as in the jarfiles of a Maven repository.
         */
        CLASSES,
        /**
         * 1424 entries of pseudo-English text, of sizes spread evenly on a log scale from 512 bytes to 128KB, 34MB
         * in all, which deflate to about a third of their size. Half of the bytes are in entries of under 66KB; in
         * the jarfiles of a Maven repository, half of the bytes of text resources (XML, properties and so on) are
         * in entries of under 67KB.
         */
        TEXT,
        /**
         * 34 entries of pseudo-random bytes, which do not compress at all, of sizes spread evenly on a log scale
         * from 64KB to 4MB, 35MB in all. Half of the bytes are in entries of under 2.2MB; in the jarfiles of a
         * Maven repository, half of the bytes of binary resources (native libraries, images and so on) are in
         * entries of under 2.8MB. Kept under {@code Vfs}'s default limit for buffering a deflated nested jarfile in
         * RAM, so that the nested jarfile is not spilled to a temporary file.
         */
        BINARY;

        /** @return the name used for this content type in file names and reports. */
        public String label() {
            return name().toLowerCase();
        }
    }

    /** How the entries are stored. */
    public enum Layout {
        /** Plain files in a directory. */
        DIR,
        /** A jarfile whose entries are stored uncompressed. */
        STORED,
        /** A jarfile whose entries are deflated. */
        DEFLATED,
        /** The deflated jarfile, stored uncompressed as {@link #INNER_JAR} within an outer jarfile. */
        NESTED_STORED,
        /** The deflated jarfile, itself deflated as {@link #INNER_JAR} within an outer jarfile. */
        NESTED_DEFLATED;

        /** @return the name used for this layout in file names and reports. */
        public String label() {
            return name().toLowerCase().replace('_', '-');
        }

        /** @return true if the entries are in a jarfile nested within another jarfile. */
        public boolean isNested() {
            return this == NESTED_STORED || this == NESTED_DEFLATED;
        }
    }

    /**
     * The version of the entries {@link #path} generates, which is part of the archive names, so that archives
     * generated by an earlier version of this class are not reused. Increment it whenever the entries change.
     */
    private static final int VERSION = 2;

    /** The total size of the entries of each jarfile that {@link #sizedPath(int)} generates. */
    public static final int SIZED_TOTAL_BYTES = 32 << 20;

    /** Words the pseudo-English text is built from. */
    private static final String[] WORDS = ("the of and to in a is that for it as was with be by on not he i this "
            + "are or his from at which but have an they you were her she there their one all we can has been if "
            + "more when will would who so no out up into do than time could about other then them these may "
            + "first any new like our over only some very what between such after most through where before "
            + "classpath module archive entry directory offset buffer channel stream thread version manifest")
            .split(" ");

    /** Generated entry content, by content type, so that each type is generated once per JVM. */
    private static final Map<Content, Map<String, byte[]>> CONTENT = new LinkedHashMap<>();

    /** Constructor. */
    private BenchmarkFixtures() {
        // Not instantiable
    }

    /** @return the directory the archives are generated into. */
    public static Path dir() {
        final var dir = System.getProperty("cgvfs.bench.dir");
        return dir != null ? Path.of(dir) : Path.of(System.getProperty("java.io.tmpdir"), "cgvfs-bench");
    }

    /**
     * The path of an archive, generating it first if it does not exist.
     *
     * @param content
     *            what the entries hold.
     * @param layout
     *            how they are stored.
     * @return the directory or outer jarfile.
     * @throws IOException
     *             if the archive could not be generated.
     */
    public static synchronized Path path(final Content content, final Layout layout) throws IOException {
        final var path = dir().resolve(
                content.label() + "-" + layout.label() + "-v" + VERSION + (layout == Layout.DIR ? "" : ".jar"));
        if (!Files.exists(path)) {
            generate(content, layout, path);
        }
        return path;
    }

    /**
     * The path of a stored jarfile of {@link #SIZED_TOTAL_BYTES} of pseudo-random entries that are all the same
     * size, generating it first if it does not exist.
     *
     * @param entrySize
     *            the size of each entry, which must divide {@link #SIZED_TOTAL_BYTES}.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be generated.
     */
    public static synchronized Path sizedPath(final int entrySize) throws IOException {
        if (entrySize <= 0 || SIZED_TOTAL_BYTES % entrySize != 0) {
            throw new IllegalArgumentException("Entry size must divide " + SIZED_TOTAL_BYTES + ": " + entrySize);
        }
        final var path = dir().resolve("sized-" + entrySize + "-stored.jar");
        if (!Files.exists(path)) {
            final var random = new Random(entrySize);
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            for (var i = 0; i < SIZED_TOTAL_BYTES / entrySize; i++) {
                final var bytes = new byte[entrySize];
                random.nextBytes(bytes);
                entries.put(String.format("sized/%06d.bin", i), bytes);
            }
            Files.createDirectories(path.getParent());
            final var tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, zip(entries, true));
            Files.move(tmp, path);
        }
        return path;
    }

    /**
     * The names of the entries, in the order they were written.
     *
     * @param content
     *            what the entries hold.
     * @return the entry names.
     * @throws IOException
     *             if the content could not be generated.
     */
    public static synchronized List<String> entryNames(final Content content) throws IOException {
        return new ArrayList<>(entries(content).keySet());
    }

    /**
     * The total size of the entries.
     *
     * @param content
     *            what the entries hold.
     * @return the number of bytes in all entries together.
     * @throws IOException
     *             if the content could not be generated.
     */
    public static synchronized long totalBytes(final Content content) throws IOException {
        var total = 0L;
        for (final var bytes : entries(content).values()) {
            total += bytes.length;
        }
        return total;
    }

    /**
     * The content of each entry, by name.
     *
     * @param content
     *            what the entries hold.
     * @return the entries.
     * @throws IOException
     *             if the content could not be generated.
     */
    private static Map<String, byte[]> entries(final Content content) throws IOException {
        var entries = CONTENT.get(content);
        if (entries == null) {
            entries = new LinkedHashMap<>();
            switch (content) {
            case CLASSES -> {
                // The jrt filesystem is shared and cannot be closed
                final var root = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base");
                final List<String> classfiles;
                try (Stream<Path> walk = Files.walk(root)) {
                    classfiles = walk.filter(p -> p.toString().endsWith(".class"))
                            .map(p -> root.relativize(p).toString()).sorted().toList();
                }
                for (var i = 0; i < 2000; i++) {
                    final var name = classfiles.get(i * classfiles.size() / 2000);
                    entries.put(name, Files.readAllBytes(root.resolve(name)));
                }
            }
            case TEXT -> {
                final var random = new Random(1);
                final var sizes = logSpacedSizes(1424, 512, 128 << 10, random);
                for (var i = 0; i < sizes.length; i++) {
                    final var sb = new StringBuilder(sizes[i]);
                    while (sb.length() < sizes[i]) {
                        // Square the uniform variate to skew toward the common words at the front
                        final var r = random.nextDouble();
                        sb.append(WORDS[(int) (r * r * WORDS.length)])
                                .append(random.nextInt(12) == 0 ? ".\n" : " ");
                    }
                    sb.setLength(sizes[i]);
                    entries.put(String.format("text/%04d.txt", i), sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            case BINARY -> {
                final var random = new Random(2);
                final var sizes = logSpacedSizes(34, 64 << 10, 4 << 20, random);
                for (var i = 0; i < sizes.length; i++) {
                    final var bytes = new byte[sizes[i]];
                    random.nextBytes(bytes);
                    entries.put(String.format("bin/%02d.bin", i), bytes);
                }
            }
            default -> throw new IllegalArgumentException(content.toString());
            }
            CONTENT.put(content, entries);
        }
        return entries;
    }

    /**
     * Entry sizes spread evenly on a log scale, in random order, so that the entries written one after another are
     * not in order of size.
     *
     * @param n
     *            the number of sizes.
     * @param min
     *            the smallest size.
     * @param max
     *            the largest size.
     * @param random
     *            the source of the order.
     * @return the sizes.
     */
    private static int[] logSpacedSizes(final int n, final int min, final int max, final Random random) {
        final List<Integer> sizes = new ArrayList<>(n);
        for (var i = 0; i < n; i++) {
            sizes.add((int) Math.round(min * Math.pow((double) max / min, (double) i / (n - 1))));
        }
        Collections.shuffle(sizes, random);
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Zip a set of entries.
     *
     * @param entries
     *            the entries, by name.
     * @param stored
     *            true to store the entries uncompressed, false to deflate them.
     * @return the zipfile's bytes.
     * @throws IOException
     *             if the entries could not be written.
     */
    private static byte[] zip(final Map<String, byte[]> entries, final boolean stored) throws IOException {
        final var bos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bos)) {
            for (final var e : entries.entrySet()) {
                final var entry = new ZipEntry(e.getKey());
                if (stored) {
                    final var crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Generate one archive.
     *
     * @param content
     *            what the entries hold.
     * @param layout
     *            how they are stored.
     * @param path
     *            where to write the directory or jarfile.
     * @throws IOException
     *             if it could not be written.
     */
    private static void generate(final Content content, final Layout layout, final Path path) throws IOException {
        final var entries = entries(content);
        Files.createDirectories(path.getParent());
        // Written under a temporary name and then moved, so that an interrupted run leaves nothing that a
        // later run would take for a complete archive
        final var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        switch (layout) {
        case DIR -> {
            for (final var e : entries.entrySet()) {
                final var file = tmp.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
            }
        }
        case STORED -> Files.write(tmp, zip(entries, true));
        case DEFLATED -> Files.write(tmp, zip(entries, false));
        case NESTED_STORED, NESTED_DEFLATED ->
            Files.write(tmp, zip(Map.of(INNER_JAR, zip(entries, false)), layout == Layout.NESTED_STORED));
        default -> throw new IllegalArgumentException(layout.toString());
        }
        Files.move(tmp, path);
    }
}
