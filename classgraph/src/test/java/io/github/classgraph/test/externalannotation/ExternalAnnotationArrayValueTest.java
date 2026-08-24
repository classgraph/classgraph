package io.github.classgraph.test.externalannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * When the classfile of the annotation class is not on the scanned classpath,
 * {@link io.github.classgraph.AnnotationParameterValue} cannot read the element type of an array-typed annotation
 * parameter from the annotation class' methods, and instead infers it from the first non-null array element. That
 * fallback did not handle {@code String} elements, and for anything it did not recognize it returned the type of
 * the value wrapper object itself rather than {@code Object}, so the parameter value was an array of the wrong
 * element type.
 */
@ExternalAnnotation({ "one", "two" })
public class ExternalAnnotationArrayValueTest {
    /**
     * The array-typed parameter of an annotation whose classfile was not scanned still gets the right element type.
     */
    @Test
    public void arrayValueElementTypeFromUnscannedAnnotationClass() throws IOException {
        // Copy just this class' own classfile into an otherwise empty directory, so that the annotation class'
        // classfile is not reachable from the scanned classpath
        final var classfilePath = ExternalAnnotationArrayValueTest.class.getName().replace('.', '/') + ".class";
        final var tempDir = Files.createTempDirectory("classgraph-test");
        try {
            final var targetFile = new File(tempDir.toFile(), classfilePath);
            assertThat(targetFile.getParentFile().mkdirs()).isTrue();
            try (var inputStream = ExternalAnnotationArrayValueTest.class.getClassLoader()
                    .getResourceAsStream(classfilePath);
                    var outputStream = Files.newOutputStream(targetFile.toPath())) {
                assertThat(inputStream).isNotNull();
                final var buf = new byte[8192];
                for (var numRead = inputStream.read(buf); numRead > 0; numRead = inputStream.read(buf)) {
                    outputStream.write(buf, 0, numRead);
                }
            }

            try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir.toFile().getPath())
                    .enableAllInfo().scan()) {
                final var classInfo = scanResult.getClassInfo(ExternalAnnotationArrayValueTest.class.getName());
                assertThat(classInfo).isNotNull();

                // The annotation class' methods were not scanned, so the element type of the array-typed annotation
                // parameter has to be inferred from the array elements
                final var annotationClassInfo = scanResult.getClassInfo(ExternalAnnotation.class.getName());
                assertThat(annotationClassInfo == null || annotationClassInfo.getMethodInfo().isEmpty()).isTrue();

                final var annotationInfo = classInfo.getAllAnnotationInfo(ExternalAnnotation.class.getName());
                assertThat(annotationInfo).isNotNull();
                final var paramValue = annotationInfo.getParameterValues().getValue("value");
                assertThat(paramValue).isInstanceOf(String[].class);
                assertThat((String[]) paramValue).containsExactly("one", "two");
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
        final var subFiles = file.listFiles();
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
