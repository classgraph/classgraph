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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Looks entries up by names whose case does not match the case of the names they are stored under.
 *
 * <p>
 * Every assertion in here holds on every operating system, whether or not its filesystem is case-insensitive: a
 * lookup by exact name is not answered by a differently-cased name anywhere, and a lookup that ignores case is
 * answered by one everywhere. The one filesystem these tests do not hold on is a case-insensitive one mounted on
 * Linux, which {@link VfsRoot#getEntry(String)} documents as the exception; no test runs on one.
 */
public class VfsEntryLookupCaseTest {
    /** The name a file is stored under, in the case it is stored in. */
    private static final String STORED_NAME = "Com/Xyz/Widget.txt";

    /** The same name, spelled in a different case. */
    private static final String DIFFERENTLY_CASED_NAME = "com/xyz/widget.txt";

    /** The content of that file. */
    private static final String CONTENT = "widget";

    /**
     * A lookup by exact name is not answered by a file whose name differs only in case, on any operating system.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aDirectoryEntryIsNotFoundUnderADifferentlyCasedName(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            assertThat(root.getEntry(DIFFERENTLY_CASED_NAME)).isNull();
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getPathFromRoot()).isEqualTo(STORED_NAME);
        }
    }

    /**
     * The same holds for a jarfile, which carries its own namespace and so answers the same question the same way
     * wherever it is read.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void aJarfileEntryIsNotFoundUnderADifferentlyCasedName(@TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(root.getEntry(DIFFERENTLY_CASED_NAME)).isNull();
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getPathFromRoot()).isEqualTo(STORED_NAME);
        }
    }

    /**
     * A lookup by exact name is not answered by a name that reaches the file by some other route, such as one with
     * a {@code "."} or {@code ".."} segment in it, or one with a repeated separator. A directory root resolves the
     * name against the filesystem, which accepts all of those, whereas a jarfile root looks the name up in a map of
     * the names its entries are stored under, so without an exact-match check the two kinds of root would answer
     * the same question differently.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath elements could not be built or read.
     */
    @Test
    public void anEntryIsNotFoundUnderANameThatIsNotTheNameItIsStoredUnder(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME);
        final var jar = makeJar(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            for (final var rootPath : new String[] { dir.toString(), jar.toString() }) {
                final var root = vfs.open(rootPath);
                assertThat(root.getEntry("Com/Xyz/../Xyz/Widget.txt")).isNull();
                assertThat(root.getEntry("./" + STORED_NAME)).isNull();
                assertThat(root.getEntry("Com//Xyz/Widget.txt")).isNull();
                assertThat(root.getEntry("/" + STORED_NAME)).isNull();
                // The name the file really is stored under is still found
                assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getPathFromRoot())
                        .isEqualTo(STORED_NAME);
            }
        }
    }

    /**
     * A lookup that ignores case finds the file, and the entry it returns is named the way the file is stored,
     * rather than the way it was asked for.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath elements could not be built or read.
     */
    @Test
    public void aDifferentlyCasedNameIsFoundWhenCaseIsIgnored(@TempDir final Path tempDir) throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME);
        final var jar = makeJar(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            for (final var rootPath : new String[] { dir.toString(), jar.toString() }) {
                final var root = vfs.open(rootPath);
                final var entry = Objects.requireNonNull(root.getEntryCaseInsensitive(DIFFERENTLY_CASED_NAME),
                        rootPath);
                assertThat(entry.getPathFromRoot()).as(rootPath).isEqualTo(STORED_NAME);
                assertThat(new String(entry.load(), StandardCharsets.UTF_8)).as(rootPath).isEqualTo(CONTENT);
                // The exact name still matches when case is ignored
                assertThat(Objects.requireNonNull(root.getEntryCaseInsensitive(STORED_NAME), rootPath)
                        .getPathFromRoot()).as(rootPath).isEqualTo(STORED_NAME);
                assertThat(root.getEntryCaseInsensitive("com/xyz/nonexistent.txt")).as(rootPath).isNull();
            }
        }
    }

    /**
     * A zipfile can store two entries whose names differ only in case, and a lookup that ignores case reports both
     * of them, in the order they appear in the zipfile, while a lookup by exact name reports one entry each.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void everyEntryThatMatchesWhenCaseIsIgnoredIsReported(@TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, STORED_NAME, DIFFERENTLY_CASED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(root.getEntriesCaseInsensitive(DIFFERENTLY_CASED_NAME)).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly(STORED_NAME, DIFFERENTLY_CASED_NAME);
            // The first entry in the zipfile is the one a single lookup finds, whichever case it was asked for in
            assertThat(
                    Objects.requireNonNull(root.getEntryCaseInsensitive(DIFFERENTLY_CASED_NAME)).getPathFromRoot())
                    .isEqualTo(STORED_NAME);
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getPathFromRoot()).isEqualTo(STORED_NAME);
            assertThat(Objects.requireNonNull(root.getEntry(DIFFERENTLY_CASED_NAME)).getPathFromRoot())
                    .isEqualTo(DIFFERENTLY_CASED_NAME);
        }
    }

    /**
     * A jarfile that stores its manifest under a lower-cased name still has a manifest, the same way
     * {@link java.util.jar.JarFile#getManifest()} still finds one.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void aManifestStoredUnderALowerCasedNameIsStillTheManifest(@TempDir final Path tempDir)
            throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            zipOut.putNextEntry(new ZipEntry("meta-inf/manifest.mf"));
            zipOut.write(
                    "Manifest-Version: 1.0\r\nClass-Path: lib/dep.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jar.toString()).getManifestEntry("Class-Path")).isEqualTo("lib/dep.jar");
        }
    }

    /**
     * A jarfile that stores a manifest under both names is described by the one under the name a manifest is
     * supposed to be stored under.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void theManifestUnderTheCanonicalNameIsThePreferredOne(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            // The lower-cased name is written first, so that entry order cannot be what decides this
            zipOut.putNextEntry(new ZipEntry("meta-inf/manifest.mf"));
            zipOut.write("Manifest-Version: 1.0\r\nClass-Path: lower.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write("Manifest-Version: 1.0\r\nClass-Path: upper.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jar.toString()).getManifestEntry("Class-Path")).isEqualTo("upper.jar");
        }
    }

    /**
     * A file reached through a symbolic link is found by the name it was reached through: the name of the file the
     * link points at is nothing like the name of the link, rather than the same name in a different case.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileReachedThroughASymbolicLinkIsFoundByTheNameItWasReachedThrough(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME);
        try {
            // A link to a directory, and a link to a file
            Files.createSymbolicLink(dir.resolve("Linked"), dir.resolve("Com"));
            Files.createSymbolicLink(dir.resolve("Alias.txt"), dir.resolve(STORED_NAME));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported here: " + e);
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            final var throughDirLink = Objects.requireNonNull(root.getEntry("Linked/Xyz/Widget.txt"));
            assertThat(new String(throughDirLink.load(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
            final var throughFileLink = Objects.requireNonNull(root.getEntry("Alias.txt"));
            assertThat(throughFileLink.getPathFromRoot()).isEqualTo("Alias.txt");
            assertThat(new String(throughFileLink.load(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
        }
    }

    /**
     * A symbolic link whose name differs from the name of the file or directory it points at only in case is found
     * by its own name, as a walk reports it and as a classloader finds it. Its real path differs from the name it
     * was reached through only in case, which is also what a case-insensitive filesystem reports for a
     * differently-cased name, so the case check has to tell the two apart. A case-insensitive filesystem cannot
     * hold both names, so there this test is skipped.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aSymbolicLinkWhoseNameDiffersFromItsTargetOnlyInCaseIsFound(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME, "Alias.txt");
        try {
            // A link to a file, and a link to a directory, each in the same directory as its target
            Files.createSymbolicLink(dir.resolve("alias.txt"), Path.of("Alias.txt"));
            Files.createSymbolicLink(dir.resolve("com"), Path.of("Com"));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Case-only symbolic links are not supported here: " + e);
            return;
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).contains("alias.txt");
            for (final var name : new String[] { "alias.txt", "Alias.txt", "com/Xyz/Widget.txt", STORED_NAME }) {
                final var entry = root.getEntry(name);
                assertThat(entry).as(name).isNotNull();
                assertThat(Objects.requireNonNull(entry).getPathFromRoot()).isEqualTo(name);
                assertThat(new String(entry.load(), StandardCharsets.UTF_8)).as(name).isEqualTo(CONTENT);
            }
            // A name that no file and no link is stored under is still not found
            assertThat(root.getEntry("com/xyz/widget.txt")).isNull();
            assertThat(root.getEntry("ALIAS.txt")).isNull();
        }
    }

    /**
     * A directory that a SecurityManager will not let be listed is treated as any other directory that cannot be
     * listed: a name whose real name differs from it only in case is taken to be a folded one, and is not found,
     * rather than the lookup failing with a {@link SecurityException}. A SecurityManager can only be installed on
     * JDK 17, so this test is skipped on later JDKs.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    @SuppressWarnings("removal")
    public void aDirectoryThatASecurityManagerWillNotListIsTreatedAsUnlisted(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), "Alias.txt");
        try {
            Files.createSymbolicLink(dir.resolve("alias.txt"), Path.of("Alias.txt"));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Case-only symbolic links are not supported here: " + e);
            return;
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            // The directory of the root, spelled as the root lists it
            final var deniedPath = Objects.requireNonNull(root.getNioPath()).toString();
            final var denyingSecurityManager = new SecurityManager() {
                @Override
                public void checkPermission(final Permission perm) {
                    // Allow everything else, including removing this SecurityManager again
                }

                @Override
                public void checkRead(final String file) {
                    if (file.equals(deniedPath)) {
                        throw new SecurityException("Read access denied: " + file);
                    }
                }
            };
            try {
                System.setSecurityManager(denyingSecurityManager);
            } catch (final UnsupportedOperationException e) {
                assumeTrue(false, "Cannot install a SecurityManager");
            }
            try {
                assertThat(root.getEntry("alias.txt")).isNull();
                assertThat(root.getEntry("Alias.txt")).isNotNull();
            } finally {
                System.setSecurityManager(null);
            }
        }
    }

    /**
     * A module exploded into a directory that is reached through a symbolic link answers a lookup the same way a
     * directory root over the same directory does. The case check compares the real path of the file with the
     * directory, so it has to be given the real path of the directory too.
     *
     * <p>
     * Only a case-insensitive filesystem tells a module root that does that from one that does not, through
     * {@link #DIFFERENTLY_CASED_NAME}. On a case-sensitive filesystem, the case-only symbolic link {@code alias.txt
     * -> Alias.txt} still takes the case check down the path that tells a link from a folded name, and has to be
     * found by both roots.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void anExplodedModuleReachedThroughASymbolicLinkAnswersLikeADirectoryRoot(@TempDir final Path tempDir)
            throws IOException {
        final var realDir = makeDir(tempDir.resolve("real"), STORED_NAME, "Alias.txt");
        final Path linkedDir;
        try {
            Files.createSymbolicLink(realDir.resolve("alias.txt"), Path.of("Alias.txt"));
            linkedDir = Files.createSymbolicLink(tempDir.resolve("linked"), realDir);
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported here: " + e);
            return;
        }
        try (var vfs = new Vfs()) {
            final var dirRoot = vfs.open(linkedDir.toString());
            final var moduleRoot = vfs.open(explodedModule(linkedDir));
            for (final var name : new String[] { STORED_NAME, DIFFERENTLY_CASED_NAME, "Alias.txt", "alias.txt" }) {
                assertThat(moduleRoot.getEntry(name) == null).as(name).isEqualTo(dirRoot.getEntry(name) == null);
            }
            assertThat(moduleRoot.getEntry(STORED_NAME)).isNotNull();
            assertThat(moduleRoot.getEntry("alias.txt")).isNotNull();
        }
    }

    /**
     * Create a module that is exploded into a directory, the way {@link ModuleFinder#of(Path...)} reports one.
     *
     * @param moduleDir
     *            the directory the module was exploded into.
     * @return the module.
     */
    private static ModuleReference explodedModule(final Path moduleDir) {
        return new ModuleReference(ModuleDescriptor.newModule("test.module").build(), moduleDir.toUri()) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(final String name) {
                        final var file = moduleDir.resolve(name);
                        return Files.isRegularFile(file) ? Optional.of(file.toUri()) : Optional.empty();
                    }

                    @Override
                    public Stream<String> list() throws IOException {
                        try (var files = Files.walk(moduleDir)) {
                            return files.filter(Files::isRegularFile)
                                    .map(file -> moduleDir.relativize(file).toString().replace('\\', '/')).toList()
                                    .stream();
                        }
                    }

                    @Override
                    public void close() {
                        // A reader that reads through the default filesystem holds nothing open between reads
                    }
                };
            }
        };
    }

    /**
     * Create a directory holding one file at each of the given names.
     *
     * @param dir
     *            the directory to create.
     * @param names
     *            the names to store the file under.
     * @return the directory.
     * @throws IOException
     *             if the directory could not be created.
     */
    private static Path makeDir(final Path dir, final String... names) throws IOException {
        for (final var name : names) {
            final var file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, CONTENT);
        }
        return dir;
    }

    /**
     * Create a jarfile holding one entry at each of the given names.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param names
     *            the names to store the entry under.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be created.
     */
    private static Path makeJar(final Path tempDir, final String... names) throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (final var name : names) {
                zipOut.putNextEntry(new ZipEntry(name));
                zipOut.write(CONTENT.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
        return jar;
    }
}
