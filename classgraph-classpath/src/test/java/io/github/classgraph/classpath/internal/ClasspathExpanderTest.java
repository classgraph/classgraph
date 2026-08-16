package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.classgraph.classpath.internal.ClasspathExpander.ChildEntry;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link ClasspathExpander}. */
public class ClasspathExpanderTest {
    /** The lib dirs to look in, in the order a classloader that supports all of them would search them. */
    private static final List<String> LIB_DIR_PREFIXES = List.of("BOOT-INF/lib/", "WEB-INF/lib/", "lib/");

    /** The name of the manifest of a jarfile or of an exploded jarfile in a directory. */
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    /**
     * Create a file, and any directories leading to it.
     *
     * @param dir
     *            the directory the path is relative to
     * @param relativePath
     *            the path of the file, relative to {@code dir}
     * @param content
     *            the content of the file
     * @return the path of the created file
     * @throws IOException
     *             if the file could not be created
     */
    private static Path createFile(final Path dir, final String relativePath, final String content)
            throws IOException {
        final Path path = dir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * Create an empty file, and any directories leading to it.
     *
     * @param dir
     *            the directory the path is relative to
     * @param relativePath
     *            the path of the file, relative to {@code dir}
     * @throws IOException
     *             if the file could not be created
     */
    private static void createFile(final Path dir, final String relativePath) throws IOException {
        createFile(dir, relativePath, "");
    }

    /**
     * The canonical form of a path. Opening a classpath element canonicalizes its path, and the temporary directory
     * is reached through a path that is not its canonical one on some platforms -- {@code "/var"} is a symlink to
     * {@code "/private/var"} on macOS, and the temporary directory is named by an 8.3 short name on Windows -- so a
     * path built from the temporary directory has to be canonicalized before a reported path is compared against
     * it.
     *
     * @param path
     *            the path to canonicalize
     * @return the canonical form of the path
     * @throws IOException
     *             if the path could not be canonicalized
     */
    private static Path canonical(final Path path) throws IOException {
        return path.toRealPath();
    }

    /**
     * The entries of a jarfile, in the order they are given.
     *
     * @param namesAndContents
     *            the name of each entry, each followed by the content of that entry
     * @return the entries, mapped from name to content, in the order they were given
     */
    private static Map<String, byte[]> entries(final String... namesAndContents) {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            entries.put(namesAndContents[i], namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    /**
     * The text of a manifest with the given attributes.
     *
     * @param attributes
     *            the attributes, each written as {@code "Name: value"}
     * @return the text of the manifest
     */
    private static String manifest(final String... attributes) {
        final StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\r\n");
        for (final String attribute : attributes) {
            manifest.append(attribute).append("\r\n");
        }
        return manifest.append("\r\n").toString();
    }

    /**
     * Write a jarfile to a stream. The entries are stored rather than deflated, since a jarfile nested inside
     * another jarfile can only be read in place if it is stored.
     *
     * @param outputStream
     *            the stream to write the jarfile to
     * @param jarEntries
     *            the entries of the jarfile, mapped from name to content
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJar(final OutputStream outputStream, final Map<String, byte[]> jarEntries)
            throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (final Map.Entry<String, byte[]> jarEntry : jarEntries.entrySet()) {
                final byte[] content = jarEntry.getValue();
                final CRC32 crc32 = new CRC32();
                crc32.update(content);
                final ZipEntry zipEntry = new ZipEntry(jarEntry.getKey());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(content.length);
                zipEntry.setCompressedSize(content.length);
                zipEntry.setCrc(crc32.getValue());
                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.write(content);
                zipOutputStream.closeEntry();
            }
        }
    }

    /**
     * The bytes of a jarfile with the given entries.
     *
     * @param jarEntries
     *            the entries of the jarfile, mapped from name to content
     * @return the bytes of the jarfile
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static byte[] jarBytes(final Map<String, byte[]> jarEntries) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeJar(bytes, jarEntries);
        return bytes.toByteArray();
    }

    /**
     * Create a jarfile with the given entries, and any directories leading to it.
     *
     * @param dir
     *            the directory the path is relative to
     * @param relativePath
     *            the path of the jarfile, relative to {@code dir}
     * @param jarEntries
     *            the entries of the jarfile, mapped from name to content
     * @return the path of the created jarfile
     * @throws IOException
     *             if the jarfile could not be created
     */
    private static Path createJar(final Path dir, final String relativePath, final Map<String, byte[]> jarEntries)
            throws IOException {
        final Path path = dir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, jarBytes(jarEntries));
        return path;
    }

    /**
     * The location of each child classpath entry, relative to the classpath element that declared it, so that the
     * separator between a classpath element and a path within it is visible.
     *
     * @param root
     *            the classpath element that declared the child classpath entries
     * @param childEntries
     *            the child classpath entries
     * @return the location of each child classpath entry, in the order they were declared
     * @throws IOException
     *             if the classpath element could not be read
     */
    private static List<String> relativeLocations(final VfsRoot root, final List<ChildEntry> childEntries)
            throws IOException {
        final String containerPath = root.getContainerRoot().getPath();
        final List<String> locations = new ArrayList<>();
        for (final ChildEntry childEntry : childEntries) {
            final String location = childEntry.location();
            locations.add(
                    location.startsWith(containerPath) ? location.substring(containerPath.length()) : location);
        }
        return locations;
    }

    /**
     * The location of each child classpath entry.
     *
     * @param childEntries
     *            the child classpath entries
     * @return the location of each child classpath entry, in the order they were declared
     */
    private static List<String> locations(final List<ChildEntry> childEntries) {
        final List<String> locations = new ArrayList<>();
        for (final ChildEntry childEntry : childEntries) {
            locations.add(childEntry.location());
        }
        return locations;
    }

    /**
     * The filename of each child classpath entry.
     *
     * @param childEntries
     *            the child classpath entries
     * @return the filename of each child classpath entry, in the order they were declared
     */
    private static List<String> leafNames(final List<ChildEntry> childEntries) {
        final List<String> leafNames = new ArrayList<>();
        for (final ChildEntry childEntry : childEntries) {
            final String location = childEntry.location();
            leafNames.add(location.substring(location.lastIndexOf('/') + 1));
        }
        return leafNames;
    }

    /**
     * A directory lists its entries in whatever order the filesystem stores them, which differs between filesystems
     * and platforms, and changes as files are added and removed. The jars of a lib dir have to be put into a fixed
     * order, otherwise the same directory produces a different classpath order on different machines, and which of
     * two jars containing the same class masks the other varies from run to run.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void libJarsAreSortedWithinALibDir(@TempDir final Path tempDir) throws IOException {
        for (final String name : new String[] { "zebra.jar", "alpha.jar", "mango.jar", "01first.jar", "beta.jar",
                "yankee.jar", "delta.jar" }) {
            createFile(tempDir, "lib/" + name);
        }
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(tempDir);
            assertThat(leafNames(ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("01first.jar", "alpha.jar", "beta.jar", "delta.jar", "mango.jar", "yankee.jar",
                            "zebra.jar");
        }
    }

    /**
     * Each lib dir is listed on its own, so that the order of the lib dir prefixes decides which lib dir's jars
     * come first -- sorting the whole result together would interleave the lib dirs, losing that precedence.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void libDirPrecedenceSurvivesSorting(@TempDir final Path tempDir) throws IOException {
        // "BOOT-INF/lib/" comes before "WEB-INF/lib/", which comes before "lib/", whatever the jars are named
        createFile(tempDir, "BOOT-INF/lib/zzz.jar");
        createFile(tempDir, "BOOT-INF/lib/mmm.jar");
        createFile(tempDir, "WEB-INF/lib/nnn.jar");
        createFile(tempDir, "lib/aaa.jar");
        createFile(tempDir, "lib/bbb.jar");
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(tempDir);
            assertThat(leafNames(ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("mmm.jar", "zzz.jar", "nnn.jar", "aaa.jar", "bbb.jar");
        }
    }

    /**
     * Only the lib dirs that were asked for are looked in, since not every classloader loads from every lib dir.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void onlyTheGivenLibDirsAreLookedIn(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "BOOT-INF/lib/boot.jar");
        createFile(tempDir, "lib/plain.jar");
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(tempDir);
            assertThat(leafNames(ClasspathExpander.childEntries(root, List.of("BOOT-INF/lib/"), true, null)))
                    .containsExactly("boot.jar");
            assertThat(ClasspathExpander.childEntries(root, List.of(), true, null)).isEmpty();
        }
    }

    /**
     * Only jarfiles are returned, and only files -- a directory whose name ends in ".jar" is not a jarfile.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void onlyJarFilesAreReturned(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "lib/a.jar");
        createFile(tempDir, "lib/notajar.txt");
        Files.createDirectories(tempDir.resolve("lib/dir.jar"));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(tempDir);
            assertThat(relativeLocations(root, ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("/lib/a.jar");
        }
    }

    /**
     * A jarfile in a subdirectory of a lib dir is on the classpath too, since a classloader that adds the jarfiles
     * of a lib dir looks below it as well. The jarfiles of a directory come before those of its subdirectories,
     * which is the order the virtual filesystem walks a directory in.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void libJarsBelowALibDirAreFound(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "lib/top.jar");
        createFile(tempDir, "lib/sub/deep.jar");
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(tempDir);
            assertThat(relativeLocations(root, ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("/lib/top.jar", "/lib/sub/deep.jar");
        }
    }

    /**
     * A directory with no lib dirs and no manifest contributes nothing.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void aDirWithNoLibDirsIsEmpty(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "com/xyz/Test.class");
        try (Vfs vfs = new Vfs()) {
            assertThat(ClasspathExpander.childEntries(vfs.open(tempDir), LIB_DIR_PREFIXES, true, null)).isEmpty();
        }
    }

    /**
     * A jarfile in a lib dir of a directory is a file of the filesystem, so it is reported with the path it is
     * stored at, which is how a classpath element in a filesystem other than the default one is opened.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void aLibJarOfADirIsReportedWithItsPath(@TempDir final Path tempDir) throws IOException {
        final Path libJar = createFile(tempDir, "lib/a.jar", "");
        try (Vfs vfs = new Vfs()) {
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(vfs.open(tempDir),
                    LIB_DIR_PREFIXES, true, null);
            assertThat(childEntries).hasSize(1);
            assertThat(childEntries.get(0).path()).isEqualTo(canonical(libJar));
        }
    }

    /**
     * The jarfiles in the lib dirs of a jarfile are on the classpath, and are reported as paths nested within the
     * jarfile that contains them, since they are not files of the filesystem.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void theLibJarsOfAJarfileAreFound(@TempDir final Path tempDir) throws IOException {
        final Path jar = createJar(tempDir, "app.jar",
                entries("BOOT-INF/lib/a.jar", "", "BOOT-INF/lib/b.jar", "", "WEB-INF/lib/c.jar", ""));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(jar);
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true,
                    null);
            assertThat(relativeLocations(root, childEntries)).containsExactly("!/BOOT-INF/lib/a.jar",
                    "!/BOOT-INF/lib/b.jar", "!/WEB-INF/lib/c.jar");
            assertThat(childEntries.get(0).path()).isNull();
        }
    }

    /**
     * Only the jarfiles in the lib dirs of a jarfile are on the classpath -- an entry that is not a jarfile, and a
     * jarfile that is not in a lib dir, are not classpath elements.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void onlyTheLibJarsOfAJarfileAreFound(@TempDir final Path tempDir) throws IOException {
        final Path jar = createJar(tempDir, "app.jar", entries("BOOT-INF/lib/a.jar", "", "BOOT-INF/lib/notajar.txt",
                "", "BOOT-INF/classes/embedded.jar", "", "elsewhere/stray.jar", ""));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(jar);
            assertThat(relativeLocations(root, ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("!/BOOT-INF/lib/a.jar");
        }
    }

    /**
     * A jarfile with no lib dirs and no manifest contributes nothing.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void aJarfileWithNoLibDirsIsEmpty(@TempDir final Path tempDir) throws IOException {
        final Path jar = createJar(tempDir, "app.jar", entries("com/xyz/Test.class", ""));
        try (Vfs vfs = new Vfs()) {
            assertThat(ClasspathExpander.childEntries(vfs.open(jar), LIB_DIR_PREFIXES, true, null)).isEmpty();
        }
    }

    /**
     * The lib dirs of a jarfile lie outside its package root, so they are found even when the classes are loaded
     * from a directory within the jarfile.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void theLibJarsOfAJarfileAreFoundOutsideItsPackageRoot(@TempDir final Path tempDir) throws IOException {
        final Path jar = createJar(tempDir, "app.jar",
                entries("BOOT-INF/classes/com/xyz/Test.class", "", "BOOT-INF/lib/a.jar", ""));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(jar + "!/BOOT-INF/classes");
            assertThat(root.getPackageRoot()).isEqualTo("BOOT-INF/classes");
            assertThat(relativeLocations(root, ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("!/BOOT-INF/lib/a.jar");
        }
    }

    /**
     * A jarfile nested inside another jarfile is only read when nested jarfiles are enabled, but a jarfile in a lib
     * dir of a directory is a file of the filesystem, so it is read either way.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void theLibJarsOfAJarfileAreSkippedWhenNestedJarsAreDisabled(@TempDir final Path tempDir)
            throws IOException {
        final Path jar = createJar(tempDir, "app.jar", entries("BOOT-INF/lib/a.jar", ""));
        final Path dir = tempDir.resolve("exploded");
        createFile(dir, "BOOT-INF/lib/a.jar");
        try (Vfs vfs = new Vfs()) {
            assertThat(ClasspathExpander.childEntries(vfs.open(jar), LIB_DIR_PREFIXES, false, null)).isEmpty();
            assertThat(ClasspathExpander.childEntries(vfs.open(dir), LIB_DIR_PREFIXES, false, null)).hasSize(1);
        }
    }

    /**
     * A {@code Class-Path} manifest entry is resolved against the directory that contains the jarfile that declared
     * it, and is reported with the path it is stored at.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void classPathManifestEntriesAreResolvedBesideTheJarfile(@TempDir final Path tempDir)
            throws IOException {
        final Path jar = createJar(tempDir, "sub/app.jar",
                entries(MANIFEST_NAME, manifest("Class-Path: dep.jar ../up.jar deeper/other.jar")));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(jar);
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true,
                    null);
            // The paths are built from the path of the jarfile itself, since the temporary directory is named
            // differently by the platform's path syntax and by the virtual filesystem's
            final String jarPath = root.getPath();
            final String jarDir = jarPath.substring(0, jarPath.lastIndexOf('/'));
            final String tempDirPath = jarDir.substring(0, jarDir.lastIndexOf('/'));
            assertThat(locations(childEntries)).containsExactly(jarDir + "/dep.jar", tempDirPath + "/up.jar",
                    jarDir + "/deeper/other.jar");
            // Each entry is also given as a path of the filesystem that the jarfile that declared it lives in,
            // including the one that climbs above the directory that contains that jarfile
            assertThat(childEntries.get(0).path()).isEqualTo(canonical(tempDir).resolve("sub/dep.jar"));
            assertThat(childEntries.get(1).path()).isEqualTo(canonical(tempDir).resolve("up.jar"));
            assertThat(childEntries.get(2).path()).isEqualTo(canonical(tempDir).resolve("sub/deeper/other.jar"));
        }
    }

    /**
     * An exploded jarfile in a directory declares the same classpath elements that the jarfile it was exploded from
     * declares, so its {@code Class-Path} manifest entries are resolved against the directory that contains it.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void classPathManifestEntriesOfADirAreResolvedBesideTheDir(@TempDir final Path tempDir)
            throws IOException {
        final Path dir = tempDir.resolve("exploded");
        createFile(dir, MANIFEST_NAME, manifest("Class-Path: dep.jar"));
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(dir);
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true,
                    null);
            assertThat(childEntries).hasSize(1);
            assertThat(childEntries.get(0).path()).isEqualTo(canonical(tempDir).resolve("dep.jar"));
        }
    }

    /**
     * A {@code Class-Path} manifest entry of a jarfile nested inside another jarfile is resolved within the jarfile
     * that contains it, so a "{@code ..}" cannot climb out of that jarfile and name a file of the filesystem.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void aClassPathManifestEntryCannotClimbOutOfTheJarfileThatContainsIt(@TempDir final Path tempDir)
            throws IOException {
        final Map<String, byte[]> outerEntries = entries();
        outerEntries.put("lib/inner.jar",
                jarBytes(entries(MANIFEST_NAME, manifest("Class-Path: ../../../../escaped.jar"))));
        final Path jar = createJar(tempDir, "outer.jar", outerEntries);
        try (Vfs vfs = new Vfs()) {
            final VfsRoot root = vfs.open(jar + "!/lib/inner.jar");
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(root, LIB_DIR_PREFIXES, true,
                    null);
            assertThat(leafNames(childEntries)).containsExactly("escaped.jar");
            assertThat(childEntries.get(0).location()).endsWith("outer.jar!/escaped.jar").doesNotContain("..");
            assertThat(childEntries.get(0).path()).isNull();
        }
    }

    /**
     * A {@code Bundle-ClassPath} manifest entry names a path within the classpath element that declared it, which
     * for a jarfile is a jarfile nested inside it, and for an exploded jarfile in a directory is a file of the
     * filesystem. A "{@code .}" entry names the classpath element itself, which is already on the classpath.
     *
     * @param tempDir
     *            the temporary directory to build the classpath element in
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void bundleClassPathManifestEntriesLieWithinTheClasspathElement(@TempDir final Path tempDir)
            throws IOException {
        final String bundleManifest = manifest("Bundle-ClassPath: .,inner.jar,/deeper/other.jar");
        final Path jar = createJar(tempDir, "bundle.jar", entries(MANIFEST_NAME, bundleManifest));
        final Path dir = tempDir.resolve("exploded");
        createFile(dir, MANIFEST_NAME, bundleManifest);
        try (Vfs vfs = new Vfs()) {
            final VfsRoot jarRoot = vfs.open(jar);
            assertThat(relativeLocations(jarRoot,
                    ClasspathExpander.childEntries(jarRoot, LIB_DIR_PREFIXES, true, null)))
                    .containsExactly("!/inner.jar", "!/deeper/other.jar");
            final VfsRoot dirRoot = vfs.open(dir);
            final List<ChildEntry> dirChildEntries = ClasspathExpander.childEntries(dirRoot, LIB_DIR_PREFIXES, true,
                    null);
            assertThat(relativeLocations(dirRoot, dirChildEntries)).containsExactly("/inner.jar",
                    "/deeper/other.jar");
            assertThat(dirChildEntries.get(0).path()).isEqualTo(canonical(dir).resolve("inner.jar"));
        }
    }

    /**
     * A {@code Class-Path} manifest entry is resolved against the jarfile that declared it, so a jarfile that lives
     * in a filesystem other than the default one declares classpath elements of that same filesystem, rather than
     * ones of the default filesystem that happen to be spelled the same way.
     *
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void classPathManifestEntriesAreResolvedInTheFilesystemOfTheJarfileThatDeclaredThem()
            throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix()); Vfs vfs = new Vfs()) {
            final Path jar = createJar(fileSystem.getPath("/app"), "sub/app.jar",
                    entries(MANIFEST_NAME, manifest("Class-Path: dep.jar ../up.jar")));
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(vfs.open(jar), LIB_DIR_PREFIXES,
                    true, null);
            assertThat(childEntries).hasSize(2);
            final Path depPath = Objects.requireNonNull(childEntries.get(0).path());
            final Path upPath = Objects.requireNonNull(childEntries.get(1).path());
            assertThat(depPath.getFileSystem()).isSameAs(fileSystem);
            assertThat(upPath.getFileSystem()).isSameAs(fileSystem);
            assertThat(depPath).isEqualTo(fileSystem.getPath("/app/sub/dep.jar"));
            // The entry that climbs above the directory that contains the jarfile stays in that filesystem too
            assertThat(upPath).isEqualTo(fileSystem.getPath("/app/up.jar"));
        }
    }

    /**
     * A directory classpath element that lives in a filesystem other than the default one declares its lib jars and
     * its {@code Bundle-ClassPath} manifest entries as paths of that same filesystem, since both of them are
     * resolved within the directory that declared them.
     *
     * @throws IOException
     *             if the classpath element could not be created or read
     */
    @Test
    public void aDirInANonDefaultFilesystemDeclaresChildrenOfThatFilesystem() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix()); Vfs vfs = new Vfs()) {
            final Path dir = fileSystem.getPath("/app");
            createFile(dir, "BOOT-INF/lib/dep.jar");
            createFile(dir, MANIFEST_NAME, manifest("Bundle-ClassPath: bundled.jar"));
            final List<ChildEntry> childEntries = ClasspathExpander.childEntries(vfs.open(dir), LIB_DIR_PREFIXES,
                    true, null);
            assertThat(childEntries).hasSize(2);
            final Path libJarPath = Objects.requireNonNull(childEntries.get(0).path());
            final Path bundledPath = Objects.requireNonNull(childEntries.get(1).path());
            assertThat(libJarPath.getFileSystem()).isSameAs(fileSystem);
            assertThat(bundledPath.getFileSystem()).isSameAs(fileSystem);
            assertThat(libJarPath).isEqualTo(fileSystem.getPath("/app/BOOT-INF/lib/dep.jar"));
            assertThat(bundledPath).isEqualTo(fileSystem.getPath("/app/bundled.jar"));
        }
    }
}
