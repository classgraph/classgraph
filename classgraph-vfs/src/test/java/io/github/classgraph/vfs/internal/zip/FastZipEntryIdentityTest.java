package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.VfsSession;

/**
 * Zip entries are used as map keys, so two of them stand for the same entry only if they name the same entry of the
 * same open zipfile. They are also sorted, so that for a multi-release jarfile the highest version of an entry
 * comes first, and so that where a zipfile holds more than one entry with the same name, the earliest one comes
 * first.
 */
public class FastZipEntryIdentityTest {
    /** The name of an entry of the base layer of the jarfile. */
    private static final String BASE_ENTRY_NAME = "testpkg/First.class";

    /** The name of the Java 9 version of {@link #BASE_ENTRY_NAME}. */
    private static final String VERSIONED_ENTRY_NAME = "META-INF/versions/9/" + BASE_ENTRY_NAME;

    /** The name of a second entry of the base layer of the jarfile. */
    private static final String SECOND_ENTRY_NAME = "testpkg/Second.class";

    /**
     * Write a jarfile holding a single entry.
     *
     * @param jarFile
     *            the jarfile to write
     * @throws Exception
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jarFile) throws Exception {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(BASE_ENTRY_NAME));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * Build an entry of the given zipfile. The entries are built by hand rather than read from a zipfile, so that
     * two entries can name the same entry of the same zipfile, and so that a versioned and an unversioned entry of
     * the same name can be compared.
     *
     * @param parentLogicalZipFile
     *            the zipfile the entry belongs to
     * @param entryName
     *            the name of the entry
     * @param locHeaderPos
     *            the position of the entry's local file header within the zipfile
     * @return the entry
     */
    private static FastZipEntry entry(final LogicalZipFile parentLogicalZipFile, final String entryName,
            final long locHeaderPos) {
        return new FastZipEntry(parentLogicalZipFile, locHeaderPos, entryName, /* isDeflated = */ false,
                /* compressedSize = */ 8L, /* uncompressedSize = */ 8L, /* lastModifiedTimeMillis = */ 0L,
                /* lastModifiedTimeMSDOS = */ 0, /* lastModifiedDateMSDOS = */ 0, /* fileAttributes = */ 0,
                /* multiReleaseVersionsEnabled = */ true);
    }

    /**
     * Open a zipfile, and run the given assertions against it.
     *
     * @param jarFile
     *            the jarfile to open
     * @param assertions
     *            the assertions to run against the opened zipfile
     * @throws Exception
     *             if the jarfile could not be opened
     */
    private static void withZipFile(final File jarFile, final ZipFileAssertions assertions) throws Exception {
        final var session = new VfsSession(new VfsSpec(), new InterruptionChecker());
        try {
            assertions.run(JarOpener.openJarFile(jarFile, session, /* log = */ null));
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            session.close(/* log = */ null);
        }
    }

    /** Assertions to run against an opened zipfile. */
    private interface ZipFileAssertions {
        /**
         * Run the assertions.
         *
         * @param logicalZipFile
         *            the opened zipfile
         * @throws Exception
         *             if an assertion could not be run
         */
        void run(LogicalZipFile logicalZipFile) throws Exception;
    }

    /** Two entries stand for the same entry if they name the same position of the same zipfile. */
    @Test
    public void anEntryIsIdentifiedByItsZipfileNameAndPosition(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "entry-identity.jar");
        writeJar(jarFile);
        withZipFile(jarFile, logicalZipFile -> {
            final var readFromZipfile = logicalZipFile.entries.get(0);
            final var sameEntry = entry(logicalZipFile, BASE_ENTRY_NAME, 0L);
            final var sameEntryAgain = entry(logicalZipFile, BASE_ENTRY_NAME, 0L);

            assertThat(readFromZipfile.entryName).isEqualTo(BASE_ENTRY_NAME);
            assertThat(sameEntry).isEqualTo(sameEntry).isEqualTo(sameEntryAgain).hasSameHashCodeAs(sameEntryAgain)
                    .isEqualTo(readFromZipfile).hasSameHashCodeAs(readFromZipfile)
                    .isNotEqualTo(readFromZipfile.entryName);

            // A different entry of the same zipfile, and the same entry at a different position, are both different
            assertThat(sameEntry).isNotEqualTo(entry(logicalZipFile, SECOND_ENTRY_NAME, 0L))
                    .isNotEqualTo(entry(logicalZipFile, BASE_ENTRY_NAME, 100L));
        });
    }

    /** An entry of one zipfile is never the same entry as an identically named entry of another zipfile. */
    @Test
    public void anEntryOfAnotherZipfileIsADifferentEntry(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "entry-identity.jar");
        writeJar(jarFile);
        final var otherJarFile = new File(tempDir, "other-entry-identity.jar");
        writeJar(otherJarFile);
        withZipFile(jarFile, logicalZipFile -> withZipFile(otherJarFile, otherLogicalZipFile -> {
            final var inOneZipfile = entry(logicalZipFile, BASE_ENTRY_NAME, 0L);
            final var inTheOtherZipfile = entry(otherLogicalZipFile, BASE_ENTRY_NAME, 0L);
            assertThat(inOneZipfile).isNotEqualTo(inTheOtherZipfile);
            // The two entries do compare equal, since they name the same entry of two different zipfiles
            assertThat(inOneZipfile).usingComparator(FastZipEntry::compareTo).isEqualTo(inTheOtherZipfile);
        }));
    }

    /**
     * Entries sort in decreasing order of multi-release version, so that the highest version of an entry masks the
     * lower ones, then by name, and finally by position within the zipfile, so that the earliest of a set of
     * identically named entries masks the later ones.
     */
    @Test
    public void entriesSortByVersionThenNameThenPosition(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "entry-order.jar");
        writeJar(jarFile);
        withZipFile(jarFile, logicalZipFile -> {
            final var base = entry(logicalZipFile, BASE_ENTRY_NAME, 200L);
            final var baseDuplicate = entry(logicalZipFile, BASE_ENTRY_NAME, 400L);
            final var versioned = entry(logicalZipFile, VERSIONED_ENTRY_NAME, 600L);
            final var second = entry(logicalZipFile, SECOND_ENTRY_NAME, 0L);

            // The version prefix is stripped from the versioned entry's unversioned name, so that it can mask the
            // base layer entry of the same name
            assertThat(versioned.version).isEqualTo(9);
            assertThat(versioned.entryNameUnversioned).isEqualTo(BASE_ENTRY_NAME);
            assertThat(base.version).isEqualTo(8);

            final List<FastZipEntry> entries = new ArrayList<>(List.of(second, baseDuplicate, base, versioned));
            Collections.sort(entries);
            assertThat(entries).containsExactly(versioned, base, baseDuplicate, second);
        });
    }

    /** An entry names itself by its path within its zipfile. */
    @Test
    public void anEntryNamesItselfByItsPathWithinItsZipfile(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "entry-path.jar");
        writeJar(jarFile);
        withZipFile(jarFile, logicalZipFile -> {
            final var zipEntry = logicalZipFile.entries.get(0);
            assertThat(zipEntry.getPath()).isEqualTo(logicalZipFile.getPath() + "!/" + BASE_ENTRY_NAME);
            // The path alone, with no URL scheme in front of it: the entry does not know that its zipfile is a file
            assertThat(zipEntry).hasToString(zipEntry.getPath());
        });
    }
}
