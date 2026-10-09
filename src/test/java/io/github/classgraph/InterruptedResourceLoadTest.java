package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A thread that is interrupted while it loads a resource from a jarfile fails to load it, but does not stop any
 * other thread from loading resources from the same jarfile, then or later. A jarfile that is not memory-mapped is
 * read through a file channel that every thread shares, and a file channel is closed by a read on a thread that
 * has been interrupted.
 */
public class InterruptedResourceLoadTest {
    /** Clear the interrupt status of the test thread, since the test threads are reused. */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    /**
     * Write a jarfile holding two small deflated entries and one large stored entry, which is read through the file
     * a buffer at a time rather than all at once.
     *
     * @param jar
     *            the jarfile to write.
     * @param largeContent
     *            the content of the stored entry.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar, final byte[] largeContent) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jar);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (final String name : new String[] { "first.txt", "second.txt" }) {
                zipOut.putNextEntry(new ZipEntry(name));
                zipOut.write(name.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
            final ZipEntry storedEntry = new ZipEntry("large.bin");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(largeContent.length);
            final CRC32 crc = new CRC32();
            crc.update(largeContent);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(largeContent);
            zipOut.closeEntry();
        }
    }

    /**
     * Get the only resource with a given path.
     *
     * @param scanResult
     *            the scan result.
     * @param path
     *            the path of the resource.
     * @return the resource.
     */
    private static Resource resource(final ScanResult scanResult, final String path) {
        final ResourceList resources = scanResult.getResourcesWithPath(path);
        assertThat(resources).hasSize(1);
        return resources.get(0);
    }

    /**
     * Load a resource on a new thread, so that the load cannot be affected by the interrupt status of the calling
     * thread.
     *
     * @param scanResult
     *            the scan result.
     * @param path
     *            the path of the resource.
     * @return the content of the resource, as a string.
     * @throws Exception
     *             if the resource could not be loaded.
     */
    private static String loadOnAnotherThread(final ScanResult scanResult, final String path) throws Exception {
        final CompletableFuture<String> content = new CompletableFuture<>();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    content.complete(new String(resource(scanResult, path).load(), StandardCharsets.UTF_8));
                } catch (final Throwable t) {
                    content.completeExceptionally(t);
                }
            }
        }).start();
        try {
            return content.get(30, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    /**
     * A thread that is interrupted while it loads a resource from a jarfile fails to load it, but does not stop any
     * other thread from loading resources from the same jarfile.
     *
     * @param tempDir
     *            the temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    @Test
    public void anInterruptedLoadDoesNotStopOtherLoadsOfTheJarfile(@TempDir final Path tempDir) throws Exception {
        final Path jar = tempDir.resolve("test.jar");
        writeJar(jar, new byte[1]);
        try (final ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan()) {
            assertThat(new String(resource(scanResult, "first.txt").load(), StandardCharsets.UTF_8))
                    .isEqualTo("first.txt");

            Thread.currentThread().interrupt();
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    resource(scanResult, "second.txt").load();
                }
            }).isInstanceOf(IOException.class);
            // The interruption is not swallowed
            assertThat(Thread.interrupted()).isTrue();

            assertThat(loadOnAnotherThread(scanResult, "second.txt")).isEqualTo("second.txt");
            // The thread that was interrupted can load resources again once its interrupt status is cleared
            assertThat(new String(resource(scanResult, "first.txt").load(), StandardCharsets.UTF_8))
                    .isEqualTo("first.txt");
        }
    }

    /**
     * A thread that is part-way through reading a resource carries on reading it after another thread is
     * interrupted while loading a resource from the same jarfile.
     *
     * @param tempDir
     *            the temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aReadInProgressIsNotStoppedByAnotherThreadsInterruptedLoad(@TempDir final Path tempDir)
            throws Exception {
        final byte[] largeContent = new byte[100_000];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i * 31);
        }
        final Path jar = tempDir.resolve("test.jar");
        writeJar(jar, largeContent);
        try (final ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan();
                final Resource largeResource = resource(scanResult, "large.bin");
                final InputStream inputStream = largeResource.open()) {
            final byte[] contentRead = new byte[largeContent.length];
            assertThat(readFully(inputStream, contentRead, 0, 1000)).isEqualTo(1000);

            // Another thread is interrupted while loading a resource from the same jarfile
            final CompletableFuture<Throwable> otherThreadFailure = new CompletableFuture<>();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Thread.currentThread().interrupt();
                    try {
                        resource(scanResult, "second.txt").load();
                        otherThreadFailure.complete(null);
                    } catch (final Throwable t) {
                        otherThreadFailure.complete(t);
                    }
                }
            }).start();
            assertThat(otherThreadFailure.get(30, TimeUnit.SECONDS)).isInstanceOf(IOException.class);

            // This thread was not interrupted, so it reads the rest of the resource
            assertThat(readFully(inputStream, contentRead, 1000, largeContent.length - 1000))
                    .isEqualTo(largeContent.length - 1000);
            assertThat(contentRead).isEqualTo(largeContent);
        }
    }

    /**
     * Read a given number of bytes from a stream, or up to the end of the stream if it is shorter.
     *
     * @param inputStream
     *            the stream to read.
     * @param buf
     *            the buffer to read into.
     * @param off
     *            the offset in the buffer to read into.
     * @param len
     *            the number of bytes to read.
     * @return the number of bytes read.
     * @throws IOException
     *             if the stream could not be read.
     */
    private static int readFully(final InputStream inputStream, final byte[] buf, final int off, final int len)
            throws IOException {
        int numBytesRead = 0;
        while (numBytesRead < len) {
            final int numBytesReadThisCall = inputStream.read(buf, off + numBytesRead, len - numBytesRead);
            if (numBytesReadThisCall < 0) {
                break;
            }
            numBytesRead += numBytesReadThisCall;
        }
        return numBytesRead;
    }
}
