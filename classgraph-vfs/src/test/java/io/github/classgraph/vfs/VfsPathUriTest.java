package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.Configuration;

import io.github.classgraph.base.LogNode;

/** Provider URIs and storage URIs have separate, explicit contracts. */
public class VfsPathUriTest {
    /**
     * An archive opened through another VFS view keeps its physical storage name and remains readable.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void archivesOpenedThroughAViewRetainStorageNaming(@TempDir final Path dir) throws IOException {
        final var jar = dir.resolve("test.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("payload"));
            zip.write(7);
            zip.closeEntry();
        }
        try (var source = new Vfs(); var reader = new Vfs()) {
            final var root = reader.open(source.open(dir).asFileSystem().getPath("/test.jar"));
            assertThat(root.getURI()).isEqualTo(jar.toUri());
            assertThat(Files.readAllBytes(root.asFileSystem().getPath("/payload"))).containsExactly((byte) 7);
        }
    }

    /**
     * Borrowed paths identify their provider without promising registration, and remain syntactically usable.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void borrowedPathUrisIdentifyTheirProviderWithoutRegistration(@TempDir final Path dir)
            throws IOException {
        Files.writeString(dir.resolve("file"), "content");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            final var path = (VfsPath) root.asFileSystem().getPath("file");
            final var uri = path.toUri();
            assertThat(uri.getScheme()).isEqualTo(path.getFileSystem().provider().getScheme());
            assertThat(path.toCgvfsUri()).isEqualTo(uri);
            assertThat(path.toStorageUri()).isEqualTo(dir.resolve("file").toUri());
            assertThatThrownBy(() -> FileSystems.getFileSystem(uri))
                    .isInstanceOf(FileSystemNotFoundException.class);
            root.close();
            assertThat(path.toUri()).isEqualTo(uri);
        }
    }

    /**
     * A path whose provider class was loaded outside the system loader still has a provider-identifying URI.
     *
     * @param dir
     *            the fixture directory.
     * @throws Exception
     *             if the isolated VFS cannot be created.
     */
    @Test
    public void uriGenerationDoesNotRequireAnInstalledProvider(@TempDir final Path dir) throws Exception {
        final var urls = new URL[] { Vfs.class.getProtectionDomain().getCodeSource().getLocation(),
                LogNode.class.getProtectionDomain().getCodeSource().getLocation() };
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            final var vfsClass = Class.forName(Vfs.class.getName(), true, loader);
            final var providerClass = Class.forName(VfsFileSystemProvider.class.getName(), true, loader);
            assertThat(providerClass.getMethod("isInstalled").invoke(null)).isEqualTo(false);
            try (var vfs = (AutoCloseable) vfsClass.getConstructor().newInstance()) {
                final var root = vfsClass.getMethod("open", Path.class).invoke(vfs, dir);
                final var fileSystem = (FileSystem) root.getClass().getMethod("asFileSystem").invoke(root);
                final var path = fileSystem.getPath("/missing");
                assertThat(path.toUri().getScheme()).isEqualTo(fileSystem.provider().getScheme());
                assertThat(path.getClass().getMethod("toCgvfsUri").invoke(path)).isEqualTo(path.toUri());
            }
        }
    }

    /**
     * Opening paths of a view retains storage naming and underlying-scheme checks, not the adapter's scheme.
     *
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void openingAViewPathPreservesStorageNamingAndSchemeChecks() throws IOException {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix());
                var source = new Vfs();
                var reader = new Vfs();
                var denied = new Vfs(new VfsSpec().denyURLScheme("jimfs"))) {
            final var dir = fileSystem.getPath("/classes");
            Files.createDirectories(dir.resolve("child"));
            Files.writeString(dir.resolve("child/file"), "content");
            final var child = (VfsPath) source.open(dir).asFileSystem().getPath("/child");
            final var opened = reader.open(child);
            assertThat(opened.getURI()).isEqualTo(child.toStorageUri());
            assertThat(Files.readString(opened.asFileSystem().getPath("/file"))).isEqualTo("content");
            assertThatThrownBy(() -> denied.open(child)).isInstanceOf(IOException.class)
                    .hasMessageContaining("The URL scheme \"jimfs:\" has been denied");
        }
    }
}
