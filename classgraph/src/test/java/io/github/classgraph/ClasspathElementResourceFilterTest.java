package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ClassGraph#acceptClasspathElementsContainingResourcePath(String...)} and
 * {@link ClassGraph#rejectClasspathElementsContainingResourcePath(String...)} look at every file in a classpath
 * element, whatever the package and path filters accept, so a directory and a jarfile with the same contents are
 * kept or dropped together.
 */
public class ClasspathElementResourceFilterTest {
    /** The files that both the directory and the jarfile hold. */
    private static final String[] FILES = { "META-INF/marker.txt", "root.txt", "p/a.txt", "q/b.txt" };

    /** An empty directory that both the directory and the jarfile hold (the jarfile as a directory entry). */
    private static final String EMPTY_DIR = "e/";

    /** The directory classpath element. */
    private File dir;

    /** The jarfile classpath element. */
    private File jar;

    /**
     * Write a directory and a jarfile that hold the same files.
     *
     * @param tempDir
     *            a temporary directory to write the classpath elements into.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @BeforeEach
    void writeClasspathElements(@TempDir final Path tempDir) throws IOException {
        final var dirPath = tempDir.resolve("classes");
        final var jarPath = tempDir.resolve("lib.jar");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jarPath.toFile()))) {
            for (final String file : FILES) {
                final var filePath = dirPath.resolve(file);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, file);
                zipOut.putNextEntry(new ZipEntry(file));
                zipOut.write(file.getBytes());
                zipOut.closeEntry();
            }
            Files.createDirectories(dirPath.resolve(EMPTY_DIR));
            zipOut.putNextEntry(new ZipEntry(EMPTY_DIR));
            zipOut.closeEntry();
        }
        dir = dirPath.toRealPath().toFile();
        jar = jarPath.toRealPath().toFile();
    }

    /**
     * Get the classpath elements that a {@link ClassGraph} keeps, given the directory and the jarfile.
     *
     * @param config
     *            the filters to set.
     * @return the classpath elements that are kept.
     */
    private List<File> keptElements(final UnaryOperator<ClassGraph> config) {
        return config.apply(new ClassGraph().enableClasspathEntries(dir, jar)).getClasspathFiles();
    }

    /** A file in a directory outside the accepted packages selects the classpath element. */
    @Test
    public void acceptedFileOutsideAcceptedPackages() {
        assertThat(keptElements(
                cg -> cg.acceptPackages("p").acceptClasspathElementsContainingResourcePath("META-INF/marker.txt")))
                .containsExactly(dir, jar);
    }

    /** A file in the package root, which is only an ancestor of the accepted packages, selects the element. */
    @Test
    public void acceptedFileInThePackageRoot() {
        assertThat(keptElements(
                cg -> cg.acceptPackages("p").acceptClasspathElementsContainingResourcePath("root.txt")))
                .containsExactly(dir, jar);
    }

    /** A glob is matched against the files outside the accepted packages too. */
    @Test
    public void acceptedGlobOutsideAcceptedPackages() {
        assertThat(keptElements(
                cg -> cg.acceptPackages("p").acceptClasspathElementsContainingResourcePath("META-INF/**")))
                .containsExactly(dir, jar);
    }

    /** A glob that matches the name of a directory, but no file, neither selects nor rejects the element. */
    @Test
    public void globMatchingOnlyADirectoryName() {
        assertThat(keptElements(cg -> cg.acceptClasspathElementsContainingResourcePath(EMPTY_DIR + "*"))).isEmpty();
        assertThat(keptElements(cg -> cg.rejectClasspathElementsContainingResourcePath(EMPTY_DIR + "*")))
                .containsExactly(dir, jar);
    }

    /** A rejected file outside the accepted packages rejects the classpath element. */
    @Test
    public void rejectedFileOutsideAcceptedPackages() {
        assertThat(keptElements(
                cg -> cg.acceptPackages("p").rejectClasspathElementsContainingResourcePath("META-INF/marker.txt")))
                .isEmpty();
        assertThat(
                keptElements(cg -> cg.acceptPackages("p").rejectClasspathElementsContainingResourcePath("q/b.txt")))
                .isEmpty();
        assertThat(keptElements(
                cg -> cg.acceptPackages("p").rejectClasspathElementsContainingResourcePath("root.txt"))).isEmpty();
    }

    /** A rejected file in a rejected path rejects the classpath element. */
    @Test
    public void rejectedFileInARejectedPath() {
        assertThat(keptElements(cg -> cg.rejectPaths("META-INF")
                .rejectClasspathElementsContainingResourcePath("META-INF/marker.txt"))).isEmpty();
    }

    /** Looking at the files outside the accepted packages does not add them to the scan. */
    @Test
    public void filesOutsideAcceptedPackagesAreNotScanned() {
        try (var scanResult = new ClassGraph().enableClasspathEntries(dir, jar).acceptPackages("p")
                .acceptClasspathElementsContainingResourcePath("META-INF/marker.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("p/a.txt", "p/a.txt");
        }
        try (var scanResult = new ClassGraph().enableClasspathEntries(dir, jar).rejectPaths("META-INF")
                .acceptClasspathElementsContainingResourcePath("META-INF/marker.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("root.txt", "p/a.txt",
                    "q/b.txt", "root.txt", "p/a.txt", "q/b.txt");
        }
    }
}
