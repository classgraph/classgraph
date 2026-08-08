package io.github.classgraph.test.externalannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * When the classfile of the annotation class is not on the scanned classpath, {@code ObjectTypedValueWrapper}
 * cannot read the element type of an array-typed annotation parameter from the annotation class' methods, and
 * instead infers it from the first non-null array element. That fallback did not handle {@code String} elements,
 * and for anything it did not recognize it returned the type of the wrapper object itself rather than
 * {@code Object}, so instantiating the annotation allocated an array of the wrong element type.
 */
@ExternalAnnotation({ "one", "two" })
public class ExternalAnnotationArrayValueTest {
    /** An annotation whose classfile was not scanned can still be instantiated if it has an array parameter. */
    @Test
    public void instantiateAnnotationWithArrayValueFromUnscannedAnnotationClass() throws IOException {
        // Copy just this class' own classfile into an otherwise empty directory, so that the annotation class'
        // classfile is not reachable from the scanned classpath
        final String classfilePath = ExternalAnnotationArrayValueTest.class.getName().replace('.', '/') + ".class";
        final Path tempDir = Files.createTempDirectory("classgraph-test");
        try {
            final File targetFile = new File(tempDir.toFile(), classfilePath);
            assertThat(targetFile.getParentFile().mkdirs()).isTrue();
            try (InputStream inputStream = ExternalAnnotationArrayValueTest.class.getClassLoader()
                    .getResourceAsStream(classfilePath); OutputStream outputStream = Files.newOutputStream(
                            targetFile.toPath())) {
                assertThat(inputStream).isNotNull();
                final byte[] buf = new byte[8192];
                for (int numRead = inputStream.read(buf); numRead > 0; numRead = inputStream.read(buf)) {
                    outputStream.write(buf, 0, numRead);
                }
            }

            try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir.toFile().getPath())
                    .enableAllInfo().scan()) {
                final ClassInfo classInfo = scanResult
                        .getClassInfo(ExternalAnnotationArrayValueTest.class.getName());
                assertThat(classInfo).isNotNull();

                // The annotation class' methods were not scanned, so the element type of the array-typed
                // annotation parameter has to be inferred from the array elements
                final ClassInfo annotationClassInfo = scanResult.getClassInfo(ExternalAnnotation.class.getName());
                assertThat(annotationClassInfo == null || annotationClassInfo.getMethodInfo().isEmpty()).isTrue();

                final AnnotationInfo annotationInfo = classInfo
                        .getAnnotationInfo(ExternalAnnotation.class.getName());
                assertThat(annotationInfo).isNotNull();
                final ExternalAnnotation annotation = (ExternalAnnotation) annotationInfo.loadClassAndInstantiate();
                assertThat(annotation.value()).containsExactly("one", "two");
            }
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    /**
     * Delete a file or directory and everything below it.
     *
     * @param file
     *            the file or directory to delete
     */
    private static void deleteRecursively(final File file) {
        final File[] subFiles = file.listFiles();
        if (subFiles != null) {
            for (final File subFile : subFiles) {
                deleteRecursively(subFile);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
