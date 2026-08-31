package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A package root of a multi-release jarfile can be named as a classpath entry even when the entries beneath it are
 * stored only under a "META-INF/versions/" prefix, since that is where they are served from once the multi-release
 * versioning has been resolved.
 */
public class MultiReleasePackageRootTest {
    /** The package root within the jarfile. */
    private static final String PACKAGE_ROOT_PREFIX = "WEB-INF/classes/";

    /** The path of the resource, relative to the package root. */
    private static final String RESOURCE_PATH = "com/xyz/resource.txt";

    /** The content of the versioned copy of the resource. */
    private static final String VERSIONED_CONTENT = "version 9";

    /**
     * A package root whose entries are all stored under a version prefix is still a package root.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aVersionedPackageRootCanBeNamedAsAClasspathEntry(@TempDir final File tempDir) throws IOException {
        // A JDK 8 JVM ignores versioned entries altogether, so there is nothing to resolve
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 9, "Multi-release jarfiles need JDK 9 or above");
        final File jarFile = new File(tempDir, "app.jar");
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(jarFile))) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write("Manifest-Version: 1.0\nMulti-Release: true\n\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
            // The resource is stored only under the version prefix, so the package root exists only there
            zipOut.putNextEntry(new ZipEntry("META-INF/versions/9/" + PACKAGE_ROOT_PREFIX + RESOURCE_PATH));
            zipOut.write(VERSIONED_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(jarFile.getPath() + "!/WEB-INF/classes").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains(RESOURCE_PATH);
            assertThat(new String(scanResult.getResourcesWithPath(RESOURCE_PATH).get(0).load(),
                    StandardCharsets.UTF_8)).isEqualTo(VERSIONED_CONTENT);
        }
    }
}
