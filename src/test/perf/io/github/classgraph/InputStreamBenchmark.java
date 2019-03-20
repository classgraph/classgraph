/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Arnaud Roger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.classgraph;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * InputStreamBenchmark.
 */
@State(Scope.Benchmark)
public class InputStreamBenchmark {
    /** The nb bytes. */
    @Param({ "16", "4096", "32178", "500000", "5000000" })
    public int nbBytes;

    /** The file. */
    public File file;

    /**
     * Setup.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Setup
    public void setUp() throws IOException {
        file = File.createTempFile("InputStreamBenchmark", ".bin");

        final Random random = new Random();
        int nb = 0;
        try (OutputStream fw = new FileOutputStream(file)) {
            while (nb < nbBytes) {
                final int toWrite = nbBytes - nb;
                final byte[] bytes = new byte[toWrite];
                random.nextBytes(bytes);
                fw.write(bytes);
                nb += toWrite;
            }
        }
    }

    /**
     * Test files.
     *
     * @param blackhole
     *            the blackhole
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Benchmark
    public void testFiles(final Blackhole blackhole) throws IOException {
        try (InputStream reader = Files.newInputStream(file.toPath())) {
            consume(reader, blackhole);
        }
    }

    /**
     * Test file channel via random file.
     *
     * @param blackhole
     *            the blackhole
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Benchmark
    public void testFileChannelViaRandomFile(final Blackhole blackhole) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            try (FileChannel open = randomAccessFile.getChannel()) {
                try (InputStream inputStream = Channels.newInputStream(open)) {
                    consume(inputStream, blackhole);
                }
            }
        }
    }

    /**
     * Test file channel.
     *
     * @param blackhole
     *            the blackhole
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Benchmark
    public void testFileChannel(final Blackhole blackhole) throws IOException {
        try (FileChannel open = FileChannel.open(file.toPath())) {
            if (open == null) {
                // Keep SpotBugs happy
                throw new NullPointerException();
            }
            try (InputStream is = Channels.newInputStream(open)) {
                consume(is, blackhole);
            }
        }
    }

    /**
     * Test file input stream.
     *
     * @param blackhole
     *            the blackhole
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Benchmark
    public void testFileInputStream(final Blackhole blackhole) throws IOException {
        try (InputStream is = Files.newInputStream(file.toPath())) {
            consume(is, blackhole);
        }
    }

    /**
     * Consume.
     *
     * @param is
     *            the is
     * @param blackhole
     *            the blackhole
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void consume(final InputStream is, final Blackhole blackhole) throws IOException {
        final byte[] buffer = new byte[4096];

        while (is.read(buffer) != -1) {
            blackhole.consume(buffer);
        }
    }

}
