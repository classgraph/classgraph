package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Resource} is the common base of the classpath element-specific resource implementations, and provides the
 * URI, URL and content accessors that they all share.
 */
public class ResourceTest {
    /** A file in the root of the test resources directory. */
    private static final String TEXT_FILE = "file-content-test.txt";

    /** The contents of {@link #TEXT_FILE}. */
    private static final String TEXT_FILE_CONTENT = "File contents";

    /** The test jar used for resources inside a jarfile. */
    private static final String JAR_NAME = "spring-boot-fully-executable-jar.jar";

    /**
     * Scan the root of the test resources directory, which is a directory classpath element.
     *
     * @return the scan result.
     */
    private static ScanResult scanTestResourcesDir() {
        return new ClassGraph().acceptPathsNonRecursive("").scan();
    }

    /**
     * Get the one resource with the given path from a scan result.
     *
     * @param scanResult
     *            the scan result.
     * @param path
     *            the resource path.
     * @return the resource.
     */
    private static Resource resource(final ScanResult scanResult, final String path) {
        final var resources = scanResult.getResourcesWithPath(path);
        assertThat(resources).as("resources with path " + path).hasSize(1);
        return resources.get(0);
    }

    /** A resource in a directory classpath element knows its path, length and content. */
    @Test
    public void aResourceInADirectoryKnowsItsPathAndContent() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var resource = resource(scanResult, TEXT_FILE);
            assertThat(resource.getPath()).isEqualTo(TEXT_FILE);
            // Only jars have a package root, so the two paths are the same for a directory
            assertThat(resource.getPathRelativeToClasspathElement()).isEqualTo(TEXT_FILE);
            assertThat(resource.getContentAsString()).isEqualTo(TEXT_FILE_CONTENT);
            assertThat(resource.getLength()).isEqualTo(TEXT_FILE_CONTENT.length());
            assertThat(resource.getLastModifiedMillis()).isPositive();
        }
    }

    /** The same content is returned by {@code open()}, {@code read()} and {@code load()}. */
    @Test
    public void theContentCanBeReadThroughEveryAccessor() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var expected = TEXT_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);

            final var forOpen = resource(scanResult, TEXT_FILE);
            try (var inputStream = forOpen.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(expected);
            }
            forOpen.close();

            // Closing the resource releases the buffer it was read into
            final var forCloseResource = resource(scanResult, TEXT_FILE);
            final var byteBuffer = forCloseResource.read().getByteBuffer();
            final var readBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(readBytes);
            assertThat(readBytes).isEqualTo(expected);
            forCloseResource.close();

            // Closing the buffer works just as well, and closes the resource it was read from
            final var forCloseBuffer = resource(scanResult, TEXT_FILE);
            try (var closeableByteBuffer = forCloseBuffer.read()) {
                final var closeableBytes = new byte[closeableByteBuffer.getByteBuffer().remaining()];
                closeableByteBuffer.getByteBuffer().get(closeableBytes);
                assertThat(closeableBytes).isEqualTo(expected);
            }

            assertThat(resource(scanResult, TEXT_FILE).load()).isEqualTo(expected);
        }
    }

    /**
     * Closing the buffer returned by {@code read()} closes the resource it was read from, so the resource can be
     * read again afterwards, and closing both the buffer and the resource releases the content only once.
     */
    @Test
    public void closingTheBufferReturnedByReadClosesTheResource() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var resource = resource(scanResult, TEXT_FILE);
            final var buffer = resource.read();
            // A resource that is still open cannot be read a second time
            assertThatThrownBy(resource::read).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already open");

            buffer.close();
            assertThat(buffer.getByteBuffer()).isNull();
            // Closing the buffer closed the resource, so it can be read again
            assertThatCode(resource::read).doesNotThrowAnyException();

            resource.close();
            assertThatCode(buffer::close).doesNotThrowAnyException();
        }
    }

    /** A resource is read through the virtual filesystem, and hands out the entry it is read from. */
    @Test
    public void aResourceHandsOutTheVfsEntryItIsReadFrom() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var resource = resource(scanResult, TEXT_FILE);
            final var entry = resource.getVfsEntry();
            assertThat(entry.getName()).isEqualTo(resource.getPath());
            assertThat(entry.getLength()).isEqualTo(TEXT_FILE_CONTENT.length());
            // The entry reads the same content, without going through the resource
            assertThat(entry.loadAsString()).isEqualTo(TEXT_FILE_CONTENT);
        }
    }

    /**
     * The same content is returned by every accessor for an entry in a jarfile too, whether the entry is deflated,
     * so that it has to be inflated into RAM to be read, or stored, so that it can be read in place.
     *
     * @param tempDir
     *            a temporary directory to write the test jarfile to
     * @throws IOException
     *             if the test jarfile cannot be written
     */
    @Test
    public void theContentOfAJarEntryCanBeReadThroughEveryAccessor(@TempDir final Path tempDir) throws IOException {
        final var content = TEXT_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
        final var jarPath = tempDir.resolve("jar-entry-content.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zipOut.putNextEntry(new ZipEntry("deflated.txt"));
            zipOut.write(content);
            zipOut.closeEntry();

            final var storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(content.length);
            storedEntry.setCompressedSize(content.length);
            final var crc = new CRC32();
            crc.update(content);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(content);
            zipOut.closeEntry();
        }

        try (var scanResult = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(jarPath).scan()) {
            for (final String entryName : new String[] { "deflated.txt", "stored.txt" }) {
                final var forOpen = resource(scanResult, entryName);
                try (var inputStream = forOpen.open()) {
                    assertThat(inputStream.readAllBytes()).as(entryName).isEqualTo(content);
                }
                forOpen.close();

                final var forCloseResource = resource(scanResult, entryName);
                final var byteBuffer = forCloseResource.read().getByteBuffer();
                final var readBytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(readBytes);
                assertThat(readBytes).as(entryName).isEqualTo(content);
                forCloseResource.close();

                final var forCloseBuffer = resource(scanResult, entryName);
                try (var closeableByteBuffer = forCloseBuffer.read()) {
                    final var closeableBytes = new byte[closeableByteBuffer.getByteBuffer().remaining()];
                    closeableByteBuffer.getByteBuffer().get(closeableBytes);
                    assertThat(closeableBytes).as(entryName).isEqualTo(content);
                }

                assertThat(resource(scanResult, entryName).load()).as(entryName).isEqualTo(content);
                assertThat(resource(scanResult, entryName).getContentAsString()).as(entryName)
                        .isEqualTo(TEXT_FILE_CONTENT);
                assertThat(resource(scanResult, entryName).getLength()).as(entryName).isEqualTo(content.length);
            }
        }
    }

    /**
     * The {@link java.nio.ByteBuffer} returned by {@link Resource#read()} covers the resource and nothing else,
     * even when the resource is a stored entry of a memory-mapped jarfile, where the mapping covers the whole
     * jarfile and the entry starts partway into it. The buffer is also read-only, since it may be a view of a
     * mapping shared by every thread reading that jarfile.
     *
     * @param tempDir
     *            a temporary directory to write the test jarfile to
     * @throws IOException
     *             if the test jarfile cannot be written or read
     */
    @Test
    public void theBufferOfAResourceCoversTheResourceAndNothingElse(@TempDir final Path tempDir)
            throws IOException {
        final var content = TEXT_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
        final var jarPath = tempDir.resolve("mapped-jar-entry.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            // A stored entry is read in place from the mapping of the jarfile; a deflated entry would instead be
            // inflated into a buffer of its own, which would not exercise the mapping
            final var storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(content.length);
            storedEntry.setCompressedSize(content.length);
            final var crc = new CRC32();
            crc.update(content);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(content);
            zipOut.closeEntry();
        }

        final var classGraph = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(jarPath);
        // Files are memory-mapped on Windows only, so the platform's choice is overridden here to exercise the
        // mapping path whatever platform this test runs on
        VfsSpecAccess.vfsSpecOf(classGraph).memoryMapFiles = true;
        try (var scanResult = classGraph.scan()) {
            final var resource = resource(scanResult, "stored.txt");
            final var byteBuffer = resource.read().getByteBuffer();
            assertThat(byteBuffer.position()).isZero();
            assertThat(byteBuffer.capacity()).isEqualTo(content.length);
            assertThat(byteBuffer.remaining()).isEqualTo(content.length);
            // Absolute reads are relative to the start of the resource, not to the start of the jarfile
            assertThat(byteBuffer.get(0)).isEqualTo(content[0]);
            assertThat(byteBuffer.isReadOnly()).isTrue();
            // Clearing the buffer must not widen it to the rest of the jarfile
            byteBuffer.clear();
            assertThat(byteBuffer.remaining()).isEqualTo(content.length);
            resource.close();
        }
    }

    /** The single-byte {@code read()} method returns byte values, not the number of bytes read. */
    @Test
    public void theContentCanBeReadOneByteAtATime() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var expected = TEXT_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
            final var resource = resource(scanResult, TEXT_FILE);
            try (var inputStream = resource.open()) {
                final var read = new byte[expected.length];
                for (var i = 0; i < expected.length; i++) {
                    final var byteVal = inputStream.read();
                    assertThat(byteVal).as("byte " + i).isBetween(0, 255);
                    read[i] = (byte) byteVal;
                }
                assertThat(read).isEqualTo(expected);
                // The stream is now at EOF
                assertThat(inputStream.read()).isEqualTo(-1);
            }
            resource.close();
        }
    }

    /**
     * Closing an {@link java.io.InputStream} that has already been closed has no effect, as required by
     * {@link java.io.InputStream#close()} -- in particular it does not close the resource a second time, which
     * would close a stream that had since been opened on the same resource.
     */
    @Test
    public void closingAnInputStreamIsIdempotent() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var expected = TEXT_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
            final var resource = resource(scanResult, TEXT_FILE);
            final var inputStream = resource.open();
            inputStream.close();

            // Reopen the same resource, then close the stale stream again -- the second close must be a no-op
            try (var reopened = resource.open()) {
                inputStream.close();
                assertThat(reopened.readAllBytes()).isEqualTo(expected);
            }
            resource.close();
        }
    }

    /** Closing a resource twice is harmless, whether or not it was ever opened. */
    @Test
    public void closingAResourceIsIdempotent() throws IOException {
        try (var scanResult = scanTestResourcesDir()) {
            final var neverOpened = resource(scanResult, TEXT_FILE);
            neverOpened.close();
            neverOpened.close();

            final var opened = resource(scanResult, TEXT_FILE);
            opened.open().close();
            opened.close();
            opened.close();
        }
    }

    /**
     * Closing a resource after the {@link ScanResult} it came from has been closed is a no-op. A resource found by
     * {@link ScanResult#getResourcesWithPathIgnoringAccept(String)} need not be an accepted resource, so it is not
     * one of the resources that the {@link ScanResult} closes, and it is still open when the scan result closes.
     */
    @Test
    public void closingAResourceAfterTheScanResultIsClosedIsANoOp() throws IOException {
        final Resource resource;
        try (var scanResult = scanTestResourcesDir()) {
            final var resources = scanResult.getResourcesWithPathIgnoringAccept(TEXT_FILE);
            assertThat(resources).as("resources with path " + TEXT_FILE).hasSize(1);
            resource = resources.get(0);
            resource.open();
        }
        assertThatCode(resource::close).doesNotThrowAnyException();
    }

    /**
     * A resource that could not be opened is left closed, rather than being left marked as open, so that opening it
     * can be tried again, and so that anything acquired for the failed attempt is released.
     *
     * @param tempDir
     *            a temporary directory to write the test file to
     * @throws IOException
     *             if the test file cannot be written or deleted
     */
    @Test
    public void aResourceThatCouldNotBeOpenedIsLeftClosed(@TempDir final Path tempDir) throws IOException {
        final var file = Files.writeString(tempDir.resolve("deleted.txt"), TEXT_FILE_CONTENT);
        try (var scanResult = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(tempDir).scan()) {
            final var resource = resource(scanResult, "deleted.txt");
            Files.delete(file);
            // The file is gone, so every attempt to read the resource fails with an IOException, and each attempt
            // fails the same way -- a resource left marked as open by the first attempt would make the second one
            // throw IllegalStateException instead
            for (var attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(resource::open).as("open").isInstanceOf(IOException.class);
                assertThatThrownBy(resource::read).as("read").isInstanceOf(IOException.class);
                assertThatThrownBy(resource::load).as("load").isInstanceOf(IOException.class);
            }
        }
    }

    /** A resource in a directory classpath element locates itself and its classpath element on the filesystem. */
    @Test
    public void aResourceInADirectoryKnowsItsLocation() {
        try (var scanResult = scanTestResourcesDir()) {
            final var resource = resource(scanResult, TEXT_FILE);
            final var classpathElementFile = resource.getClasspathElementFile();
            assertThat(classpathElementFile).isDirectory();
            assertThat(new java.io.File(classpathElementFile, TEXT_FILE)).isFile();

            // The classpath element URI of a directory ends in "/", so the resource path is appended directly
            assertThat(resource.getClasspathElementURI().toString()).endsWith("/");
            assertThat(Path.of(resource.getClasspathElementURI())).isEqualTo(classpathElementFile.toPath());
            assertThat(Path.of(resource.getURI())).isEqualTo(classpathElementFile.toPath().resolve(TEXT_FILE));

            // The URL forms of the same locations (a URL renders a "file:" URI without its empty authority, so the
            // string forms differ from the URI string forms by two slashes). The path of a "file:" URL is the URI
            // path, not the filesystem path -- on Windows they differ, since the URI path uses '/' as its
            // separator and carries a leading slash before the drive letter, as in "/C:/dir/"
            final var classpathElementUriPath = classpathElementFile.toURI().getRawPath();
            assertThat(resource.getClasspathElementURL().getProtocol()).isEqualTo("file");
            assertThat(resource.getClasspathElementURL().getPath()).isEqualTo(classpathElementUriPath);
            assertThat(resource.getURL().getPath()).isEqualTo(classpathElementUriPath + TEXT_FILE);

            // A ModuleReference is only present for resources read from the module path
            assertThat(resource.getModuleReference()).isNull();
        }
    }

    /** POSIX file permissions are readable for a resource on a POSIX filesystem. */
    @Test
    public void posixFilePermissionsAreReadableOnAPosixFilesystem() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        try (var scanResult = scanTestResourcesDir()) {
            assertThat(resource(scanResult, TEXT_FILE).getPosixFilePermissions()).isNotEmpty();
        }
    }

    /**
     * The POSIX file permissions of a resource in a zipfile are decoded from the Unix mode bits in the zip entry's
     * external file attributes, so they are readable whatever filesystem the zipfile is stored on.
     *
     * @param tempDir
     *            a temporary directory to write the test zipfile to
     * @throws IOException
     *             if the test zipfile cannot be written
     */
    @Test
    public void posixFilePermissionsOfAResourceInAZipComeFromItsModeBits(@TempDir final Path tempDir)
            throws IOException {
        // "rwxr-x--x" is 0751 -- a different value in each of the three triplets, so a decoder that read the mode
        // bits in the wrong order would produce a different permission set
        final var permissions = PosixFilePermissions.fromString("rwxr-x--x");
        final var zipPath = tempDir.resolve("posix-file-permissions.jar");
        try (var zipFileSystem = FileSystems.newFileSystem(URI.create("jar:" + zipPath.toUri()),
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            final var entry = zipFileSystem.getPath("/withPermissions.txt");
            Files.writeString(entry, TEXT_FILE_CONTENT);
            Files.setPosixFilePermissions(entry, permissions);
        }
        try (var scanResult = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(zipPath).scan()) {
            assertThat(resource(scanResult, "withPermissions.txt").getPosixFilePermissions())
                    .isEqualTo(permissions);
        }
    }

    /** A resource inside a jar has a {@code "jar:...!/"} URI, and a path relative to the package root. */
    @Test
    public void aResourceInAJarHasAJarURI() throws IOException {
        final var jarURL = ResourceTest.class.getClassLoader().getResource(JAR_NAME);
        assertThat(jarURL).isNotNull();
        try (var scanResult = new ClassGraph().acceptPathsNonRecursive("hello")
                .overrideClasspath("jar:" + jarURL + "!/BOOT-INF/classes").scan()) {
            final var resource = resource(scanResult, "hello/HelloController.class");
            // getPath() is relative to the package root, getPathRelativeToClasspathElement() to the jar root
            assertThat(resource.getPath()).isEqualTo("hello/HelloController.class");
            assertThat(resource.getPathRelativeToClasspathElement())
                    .isEqualTo("BOOT-INF/classes/hello/HelloController.class");
            // The URI is formed from the classpath element URI, the "!/" separator, and the path within the jar
            assertThat(resource.getURI().toString()).startsWith("jar:file:")
                    .endsWith(JAR_NAME + "!/BOOT-INF/classes/hello/HelloController.class");
            assertThat(resource.getURL().toString()).isEqualTo(resource.getURI().toString());
            assertThat(resource.getClasspathElementFile().getName()).isEqualTo(JAR_NAME);
            assertThat(resource.getModuleReference()).isNull();
            // A classfile starts with the 0xCAFEBABE magic number
            assertThat(resource.load()).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE);
        }
    }

    /**
     * A resource in a system module has a {@code "jrt:"} URI. The JDK has registered a URL protocol handler for the
     * {@code "jrt:"} scheme ever since modules were introduced in JDK 9, so the URI converts to a working
     * {@link java.net.URL}, and the resource can be read through it.
     */
    @Test
    public void aResourceInASystemModuleHasAJrtURI() throws IOException {
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules()
                .acceptPathsNonRecursive("java/util/function").scan()) {
            final var resource = resource(scanResult, "java/util/function/Function.class");
            assertThat(resource.getModuleReference()).isNotNull();
            assertThat(resource.getModuleReference().descriptor().name()).isEqualTo("java.base");
            // Not a file on disk, so there is no classpath element File
            assertThat(resource.getClasspathElementFile()).isNull();

            assertThat(resource.getClasspathElementURI().toString()).isEqualTo("jrt:/java.base");
            // A "jrt:" classpath element URI does not end in "/", so a "/" is inserted before the resource path
            assertThat(resource.getURI().toString()).isEqualTo("jrt:/java.base/java/util/function/Function.class");

            assertThat(resource.getClasspathElementURL().toString()).isEqualTo("jrt:/java.base");
            try (var inputStream = resource.getURL().openStream()) {
                assertThat(inputStream.readNBytes(4)).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA,
                        (byte) 0xBE);
            }
        }
    }

    /** Resources are equal, and sort together, when they have the same URI. */
    @Test
    public void resourcesAreComparedByURI() {
        try (var scanResult = scanTestResourcesDir(); var scanResult2 = scanTestResourcesDir()) {
            final var resource = resource(scanResult, TEXT_FILE);
            final var sameResource = resource(scanResult2, TEXT_FILE);
            assertThat(sameResource).isNotSameAs(resource).isEqualTo(resource).hasSameHashCodeAs(resource);
            assertThat(resource.compareTo(sameResource)).isZero();
            // toString() is the resource URI, and is cached after the first call
            assertThat(resource.toString()).isEqualTo(resource.getURI().toString()).isEqualTo(resource.toString());

            final var otherResource = resource(scanResult, "issue209.jar");
            assertThat(resource).isNotEqualTo(otherResource).isNotEqualTo(resource.toString());
            assertThat(resource.compareTo(otherResource))
                    .isEqualTo(resource.toString().compareTo(otherResource.toString()));
        }
    }
}
