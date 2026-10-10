package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A classfile compiled for a newer JDK than the one running the scan, such as build output compiled for JDK N+1 and
 * scanned by a tool on JDK N, or the {@code META-INF/versions/N+1} entries of a multi-release jar, must still be
 * scanned in full. The classfile version is recorded, but not checked.
 */
public class FutureClassfileVersionTest {
    /** The class whose classfile is copied with a changed version. */
    @Deprecated
    public static class Sample {
        /** A field. */
        public int field;

        /** A method. */
        public void method() {
            // Empty
        }
    }

    /**
     * Copy the classfile of {@link Sample} into a directory under its package path, with its major version changed,
     * scan the directory, and check that the class was read in full.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @param majorVersion
     *            the major version to write into the classfile.
     * @throws IOException
     *             if the classfile could not be read or written.
     */
    private static void scanWithMajorVersion(final Path tempDir, final int majorVersion) throws IOException {
        final var classfileName = Sample.class.getName().replace('.', '/') + ".class";
        final byte[] bytes;
        try (var in = Sample.class.getClassLoader().getResourceAsStream(classfileName)) {
            assertThat(in).isNotNull();
            bytes = in.readAllBytes();
        }
        // Bytes 6 and 7 hold the major version, big-endian
        bytes[6] = (byte) (majorVersion >> 8);
        bytes[7] = (byte) majorVersion;
        final var classfilePath = tempDir.resolve(classfileName);
        Files.createDirectories(classfilePath.getParent());
        Files.write(classfilePath, bytes);

        try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Sample.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getClassfileMajorVersion()).isEqualTo(majorVersion);
            assertThat(classInfo.getDeclaredFieldInfo().getNames()).containsExactly("field");
            assertThat(classInfo.getDeclaredMethodInfo().getNames()).containsExactly("method");
            assertThat(classInfo.getAllAnnotationInfo().getNames()).containsExactly(Deprecated.class.getName());
        }
    }

    /**
     * A classfile whose major version is one above the running JDK's is scanned.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be read or written.
     */
    @Test
    void nextJdkVersion(@TempDir final Path tempDir) throws IOException {
        // The major version of JDK N's classfiles is N + 44
        scanWithMajorVersion(tempDir, Runtime.version().feature() + 44 + 1);
    }

    /**
     * A classfile with the largest possible major version is scanned.
     *
     * @param tempDir
     *            the directory to write the classfile to.
     * @throws IOException
     *             if the classfile could not be read or written.
     */
    @Test
    void largestVersion(@TempDir final Path tempDir) throws IOException {
        scanWithMajorVersion(tempDir, 0xFFFF);
    }
}
