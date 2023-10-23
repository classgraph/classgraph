package io.github.classgraph.issues.issue804;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue 804.
 */
public class Issue804Test {

	private static final String NESTED_EXAMPLE_CLASS = "org.springframework.util.ResourceUtils";

	@Test
	void scanningNestedJarsInPathsContainingSpacesShouldNeverFail(@TempDir Path tempDir) throws IOException {
		Path targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory with spaces");

		try (ScanResult scanResult = scanJar(targetJar)) {
			assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
		}
	}

	@Test
	void scanningNestedJarsInPathsContainingHashesShouldNeverFail(@TempDir Path tempDir) throws IOException {
		Path targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory-without-spaces#123");

		try (ScanResult scanResult = scanJar(targetJar)) {
			assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
		}
	}

	@Test
	void scanningNestedJarsInPathsContainingSpacesAndHashesShouldNeverFail(@TempDir Path tempDir) throws IOException {
		Path targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory with spaces #123");

		try (ScanResult scanResult = scanJar(targetJar)) {
			assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
		}
	}

	private Path createSpringBootJarInExampleDirectory(Path temporaryDirectory, String directoryName)
			throws IOException {
		Path directoryWithSpaces = temporaryDirectory.resolve(directoryName);
		Files.createDirectories(directoryWithSpaces);
		Path nestedJar = directoryWithSpaces.resolve("spring-boot-fully-executable-jar.jar");
		try (InputStream nestedJarsExample = Issue804Test.class.getClassLoader()
				.getResourceAsStream("spring-boot-fully-executable-jar.jar")) {
			Files.copy(nestedJarsExample, nestedJar);
		}
		return nestedJar;
	}

	private ScanResult scanJar(Path targetJar) {
		return new ClassGraph().enableClassInfo().overrideClasspath(targetJar.toUri()).scan();
	}

}
