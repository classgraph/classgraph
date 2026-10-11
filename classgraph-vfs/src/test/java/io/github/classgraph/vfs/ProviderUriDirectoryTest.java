package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.vfs.internal.zip.ProviderOnlySchemeFileSystemProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A directory named by a URI whose scheme an installed filesystem provider serves is opened as a directory, by
 * {@link Vfs#open(URI)}, {@link Vfs#open(java.net.URL)} and {@link Vfs#open(String)}, just as
 * {@link Vfs#open(Path)} opens the {@link Path} the provider returns for it.
 */
public class ProviderUriDirectoryTest {
    /** The path of the entry written into every directory under test. */
    private static final String ENTRY_PATH = "com/xyz/widget.txt";

    /** The content of that entry. */
    private static final String ENTRY_CONTENT = "provider-uri-directory-test";

    /**
     * Write {@link #ENTRY_PATH} into a directory.
     *
     * @param dir
     *            the directory.
     * @return the directory.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static Path writeDir(final Path dir) throws IOException {
        Files.createDirectories(dir.resolve("com/xyz"));
        Files.writeString(dir.resolve(ENTRY_PATH), ENTRY_CONTENT);
        return dir;
    }

    /**
     * Read {@link #ENTRY_PATH} from a root.
     *
     * @param root
     *            the root.
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static String entryContent(final VfsRoot root) throws IOException {
        return Objects.requireNonNull(root.getEntry(ENTRY_PATH)).loadAsString();
    }

    /**
     * A directory at a URI whose scheme has a filesystem provider but no URL stream handler is opened as a
     * directory, and is the same root as the directory opened by its path.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void aDirectoryAtAUriThatOnlyAFilesystemProviderServesIsOpened(@TempDir final Path tempDir)
            throws IOException {
        final var dir = writeDir(tempDir.resolve("classes"));
        final var uri = ProviderOnlySchemeFileSystemProvider.uriOf(dir);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(uri);
            assertThat(root).isInstanceOf(DirRoot.class);
            assertThat(entryContent(root)).isEqualTo(ENTRY_CONTENT);
            assertThat(vfs.open(uri.toString())).isSameAs(root);
            // The provider names a directory of the default filesystem, which is opened once however it is named
            assertThat(vfs.open(dir)).isSameAs(root);
            assertThat(vfs.open(dir.toString())).isSameAs(root);
        }
    }

    /**
     * A directory of a filesystem other than the default one is opened by its URI, its URL or the string form of
     * either, and is the same root as the directory opened by its {@link Path}.
     *
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void aDirectoryInANonDefaultFilesystemIsOpenedByItsUriOrUrl() throws IOException {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix()); var vfs = new Vfs()) {
            final var dir = writeDir(fileSystem.getPath("/classes"));
            final var uri = dir.toUri();
            final var root = vfs.open(uri);
            assertThat(root).isInstanceOf(DirRoot.class);
            assertThat(root.getNioPath()).isEqualTo(dir);
            assertThat(entryContent(root)).isEqualTo(ENTRY_CONTENT);
            assertThat(vfs.open(uri.toURL())).isSameAs(root);
            assertThat(vfs.open(uri.toString())).isSameAs(root);
            assertThat(vfs.open(dir)).isSameAs(root);
        }
    }

    /**
     * A directory reached through a filesystem other than the default one is not recorded under the local path that
     * the filesystem reports it at, since that path names the storage behind the filesystem, not the filesystem,
     * which the caller may close. Here a {@code "cgvfs:"} filesystem is mounted on a local directory, and reports
     * its top directory at that local directory's path.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     * @throws URISyntaxException
     *             if the URI could not be built.
     */
    @Test
    public void aDirectoryInANonDefaultFilesystemDoesNotTakeOverTheLocalPathItIsBackedBy(
            @TempDir final Path tempDir) throws IOException, URISyntaxException {
        final var dir = writeDir(tempDir.resolve("classes"));
        final var cgvfsUri = new URI(VfsFileSystemProvider.SCHEME,
                FastPathResolver.normalizePath(dir.toString(), /* percentDecode = */ false), /* fragment = */ null);
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri, Map.of()); var vfs = new Vfs()) {
            final var root = vfs.open(cgvfsUri);
            assertThat(root).isInstanceOf(DirRoot.class);
            assertThat(Objects.requireNonNull(root.getNioPath()).getFileSystem()).isSameAs(fileSystem);
            assertThat(entryContent(root)).isEqualTo(ENTRY_CONTENT);
            final var localRoot = vfs.open(dir.toString());
            assertThat(localRoot).isNotSameAs(root);
            assertThat(Objects.requireNonNull(localRoot.getNioPath()).getFileSystem())
                    .isSameAs(FileSystems.getDefault());
        }
    }

    /**
     * A denied scheme is refused for a directory, as it is for a jarfile.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    @Test
    public void aDeniedSchemeIsRefusedForADirectory(@TempDir final Path tempDir) throws IOException {
        final var dir = writeDir(tempDir.resolve("classes"));
        final var uri = ProviderOnlySchemeFileSystemProvider.uriOf(dir);
        try (var vfs = new Vfs(new VfsSpec().denyURLScheme(ProviderOnlySchemeFileSystemProvider.SCHEME))) {
            assertThatThrownBy(() -> vfs.open(uri)).isInstanceOf(IOException.class).hasMessageContaining(
                    "Fetching a jarfile over \"" + ProviderOnlySchemeFileSystemProvider.SCHEME + ":\"");
        }
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix());
                var vfs = new Vfs(new VfsSpec().denyURLScheme("jimfs"))) {
            final var jimfsDir = writeDir(fileSystem.getPath("/classes"));
            assertThatThrownBy(() -> vfs.open(jimfsDir.toUri())).isInstanceOf(IOException.class)
                    .hasMessageContaining("Fetching a jarfile over \"jimfs:\"");
        }
    }

    /**
     * A URI that no filesystem provider can turn into a directory is opened as a jarfile, and fails the way a
     * jarfile at that URI fails: here once because the provider rejects the URI, and once because no provider or
     * URL stream handler is installed for the scheme.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    @Test
    public void aUriThatNoProviderTurnsIntoADirectoryIsOpenedAsAJarfile(@TempDir final Path tempDir)
            throws IOException {
        final var dir = writeDir(tempDir.resolve("classes"));
        try (var vfs = new Vfs()) {
            // The provider maps the URI to a "file:" URI, which has to be hierarchical
            assertThatThrownBy(() -> vfs.open(ProviderOnlySchemeFileSystemProvider.SCHEME + ":relative/dir"))
                    .isInstanceOf(IOException.class).hasMessageContaining("Could not convert URL to a path");
            assertThatThrownBy(() -> vfs.open("cgnoprovider:" + dir.toUri().getRawSchemeSpecificPart()))
                    .isInstanceOf(IOException.class).hasMessageContaining("Could not parse URL")
                    .hasMessageContaining("unknown protocol");
        }
    }
}
