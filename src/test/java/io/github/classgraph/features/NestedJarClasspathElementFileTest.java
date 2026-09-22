package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The classpath element file of a resource in a jarfile nested within another jarfile is the outermost jarfile,
 * whether the nested jarfile is stored in it or deflated, and the outermost jarfile is listed once among the
 * classpath files however many jarfiles are nested within it.
 */
public class NestedJarClasspathElementFileTest {
    /**
     * Write a jarfile with one resource, whose content is its name.
     *
     * @param resourcePath
     *            the path of the resource.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] jarWithResource(final String resourcePath) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(bytes)) {
            zipOut.putNextEntry(new ZipEntry(resourcePath));
            zipOut.write(resourcePath.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * A resource in a stored nested jarfile and one in a deflated nested jarfile report the same outermost jarfile.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void storedAndDeflatedNestedJarfilesReportTheOutermostJarfile(@TempDir final File tempDir)
            throws IOException {
        final File outerJarFile = new File(tempDir, "app.jar");
        final byte[] storedJar = jarWithResource("stored.txt");
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(outerJarFile))) {
            final ZipEntry storedEntry = new ZipEntry("lib/stored.jar");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(storedJar.length);
            final CRC32 crc = new CRC32();
            crc.update(storedJar);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(storedJar);
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("lib/deflated.jar"));
            zipOut.write(jarWithResource("deflated.txt"));
            zipOut.closeEntry();
        }

        final List<String> classpath = Arrays.asList(outerJarFile + "!/lib/stored.jar",
                outerJarFile + "!/lib/deflated.jar");
        final File expected = outerJarFile.getCanonicalFile();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpath).scan()) {
            assertThat(scanResult.getResourcesWithPath("stored.txt").get(0).getClasspathElementFile())
                    .isEqualTo(expected);
            assertThat(scanResult.getResourcesWithPath("deflated.txt").get(0).getClasspathElementFile())
                    .isEqualTo(expected);
            // Both nested jarfiles are in the same outermost jarfile, which is listed once
            assertThat(scanResult.getClasspathFiles()).containsExactly(expected);
        }
        assertThat(new ClassGraph().overrideClasspath(classpath).getClasspathFiles()).containsExactly(expected);
    }
}
