package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that the version of a Maven artifact is found from whichever of the three places it is recorded in: the
 * {@code pom.xml} of the project the artifact was built from, the {@code pom.properties} Maven writes into the jar,
 * or the jar manifest.
 */
public class VersionFinderTest {
    /** The group ID used for the artifacts built by these tests. */
    private static final String GROUP_ID = "com.xyz";

    /** The artifact ID used for the artifacts built by these tests. */
    private static final String ARTIFACT_ID = "test-artifact";

    /** The fully-qualified name of the class compiled into the artifacts built by these tests. */
    private static final String CLASS_NAME = "com.xyz.ClassInArtifact";

    /**
     * Build an artifact laid out the way Maven lays out a build directory: the compiled classes in
     * {@code target/classes}, with the {@code pom.xml} two directories above them.
     *
     * @param projectDir
     *            the project directory to build the artifact in.
     * @param pomXmlContent
     *            the content of the {@code pom.xml}, or null not to write one.
     * @return the directory the classes were compiled into.
     * @throws IOException
     *             if the artifact could not be built.
     */
    private static Path buildArtifact(final Path projectDir, final String pomXmlContent) throws IOException {
        final var sourceFile = projectDir.resolve("ClassInArtifact.java");
        Files.writeString(sourceFile, """
                package com.xyz;

                public class ClassInArtifact {
                }
                """);
        final var classesDir = projectDir.resolve("target").resolve("classes");
        Files.createDirectories(classesDir);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, "-d", classesDir.toString(),
                sourceFile.toString())).as("javac exit code").isZero();
        if (pomXmlContent != null) {
            Files.writeString(projectDir.resolve("pom.xml"), pomXmlContent);
        }
        return classesDir;
    }

    /**
     * The version that is found for the class in an artifact, loaded from the given classpath element.
     *
     * @param classpathElement
     *            the directory the artifact's classes were compiled into, or the jar they were packaged into.
     * @return the version, or {@code "unknown"} if no version was found.
     * @throws Exception
     *             if the class could not be loaded.
     */
    private static String versionOfArtifactIn(final Path classpathElement) throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { classpathElement.toUri().toURL() },
                /* parent = */ VersionFinderTest.class.getClassLoader())) {
            return VersionFinder.getVersion(Class.forName(CLASS_NAME, /* initialize = */ false, classLoader),
                    GROUP_ID, ARTIFACT_ID);
        }
    }

    /**
     * A {@code pom.xml} with the given body inside its {@code <project>} element, in the Maven POM namespace that a
     * real {@code pom.xml} declares.
     *
     * @param projectBody
     *            the content of the {@code <project>} element.
     * @return the {@code pom.xml} content.
     */
    private static String pomXml(final String projectBody) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                """ + projectBody + "\n</project>\n";
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * The version of an artifact that is being run from a build directory is read from the {@code pom.xml} of the
     * project it was built from.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void theVersionOfAnArtifactRunFromABuildDirectoryIsReadFromItsPomXml(@TempDir final Path tempDir)
            throws Exception {
        final var classesDir = buildArtifact(tempDir, pomXml("""
                <groupId>com.xyz</groupId>
                <artifactId>test-artifact</artifactId>
                <version>1.2.3</version>"""));
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("1.2.3");
    }

    /**
     * A module of a multi-module build usually omits its own version and inherits the parent's, so the parent's
     * version is used when the module declares none.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void aModuleThatInheritsItsVersionFromItsParentReportsTheParentsVersion(@TempDir final Path tempDir)
            throws Exception {
        final var classesDir = buildArtifact(tempDir, pomXml("""
                <parent>
                    <groupId>com.xyz</groupId>
                    <artifactId>test-parent</artifactId>
                    <version>4.5.6</version>
                </parent>
                <artifactId>test-artifact</artifactId>"""));
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("4.5.6");
    }

    /**
     * A module's own version wins over the version it would otherwise inherit from its parent.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void aModulesOwnVersionWinsOverItsParentsVersion(@TempDir final Path tempDir) throws Exception {
        final var classesDir = buildArtifact(tempDir, pomXml("""
                <parent>
                    <groupId>com.xyz</groupId>
                    <artifactId>test-parent</artifactId>
                    <version>4.5.6</version>
                </parent>
                <artifactId>test-artifact</artifactId>
                <version>1.2.3</version>"""));
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("1.2.3");
    }

    /**
     * A {@code pom.xml} that declares no namespace is read the same way as one that declares the Maven POM
     * namespace.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void aPomXmlThatDeclaresNoNamespaceIsReadTheSameWay(@TempDir final Path tempDir) throws Exception {
        final var classesDir = buildArtifact(tempDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <version>7.8.9</version>
                </project>
                """);
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("7.8.9");
    }

    /**
     * A blank version element is treated as no version at all, rather than as a blank version number.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void aBlankVersionIsTreatedAsNoVersion(@TempDir final Path tempDir) throws Exception {
        final var classesDir = buildArtifact(tempDir, pomXml("""
                <artifactId>test-artifact</artifactId>
                <version>   </version>"""));
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("unknown");
    }

    /**
     * Whitespace around the version number is trimmed, since a {@code pom.xml} is usually indented.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void whitespaceAroundTheVersionNumberIsTrimmed(@TempDir final Path tempDir) throws Exception {
        final var classesDir = buildArtifact(tempDir, pomXml("""
                <artifactId>test-artifact</artifactId>
                <version>
                    1.2.3
                </version>"""));
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("1.2.3");
    }

    /**
     * An artifact with no {@code pom.xml}, no {@code pom.properties} and no manifest has no version to report, and
     * says so rather than failing.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void anArtifactThatRecordsNoVersionAnywhereReportsThatItsVersionIsUnknown(@TempDir final Path tempDir)
            throws Exception {
        final var classesDir = buildArtifact(tempDir, /* pomXmlContent = */ null);
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("unknown");
    }

    /**
     * An artifact that is not being run from a build directory has no {@code pom.xml}, so its version is read from
     * the {@code pom.properties} that Maven writes into the jar.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void theVersionOfAPackagedArtifactIsReadFromItsMavenProperties(@TempDir final Path tempDir)
            throws Exception {
        final var classesDir = buildArtifact(tempDir, /* pomXmlContent = */ null);
        final var mavenDir = classesDir.resolve("META-INF").resolve("maven").resolve(GROUP_ID).resolve(ARTIFACT_ID);
        Files.createDirectories(mavenDir);
        Files.writeString(mavenDir.resolve("pom.properties"), """
                groupId=com.xyz
                artifactId=test-artifact
                version=2.3.4
                """);
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("2.3.4");
    }

    /**
     * The {@code pom.properties} of a different artifact is not read -- the properties file is looked for under the
     * group and artifact ID of the artifact whose version was asked for.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void theMavenPropertiesOfADifferentArtifactAreNotRead(@TempDir final Path tempDir) throws Exception {
        final var classesDir = buildArtifact(tempDir, /* pomXmlContent = */ null);
        final var mavenDir = classesDir.resolve("META-INF").resolve("maven").resolve("com.other")
                .resolve("other-artifact");
        Files.createDirectories(mavenDir);
        Files.writeString(mavenDir.resolve("pom.properties"), "version=9.9.9\n");
        assertThat(versionOfArtifactIn(classesDir)).isEqualTo("unknown");
    }

    /**
     * Package an artifact's classes into a jar, with the given attributes in the jar manifest.
     *
     * @param projectDir
     *            the project directory the artifact was built in.
     * @param manifestAttributes
     *            the attributes to write into the main section of the jar manifest.
     * @return the jar file.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static Path packageArtifactAsJar(final Path projectDir, final String manifestAttributes)
            throws IOException {
        final var classesDir = projectDir.resolve("target").resolve("classes");
        final var jarFile = projectDir.resolve("target").resolve(ARTIFACT_ID + ".jar");
        final var manifest = new Manifest(new ByteArrayInputStream(
                ("Manifest-Version: 1.0\n" + manifestAttributes + "\n").getBytes(StandardCharsets.UTF_8)));
        try (var jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile), manifest);
                var classFiles = Files.walk(classesDir)) {
            for (final var classFile : classFiles.filter(Files::isRegularFile).toList()) {
                jarOutputStream.putNextEntry(
                        new JarEntry(classesDir.relativize(classFile).toString().replace(File.separatorChar, '/')));
                jarOutputStream.write(Files.readAllBytes(classFile));
                jarOutputStream.closeEntry();
            }
        }
        return jarFile;
    }

    /**
     * An artifact that is packaged in a jar with no Maven metadata, but with a version in its manifest, reports the
     * version from the manifest -- the jars of some build systems record the version only there.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void theVersionInAJarManifestIsUsedWhenThereIsNoMavenMetadata(@TempDir final Path tempDir)
            throws Exception {
        buildArtifact(tempDir, /* pomXmlContent = */ null);
        assertThat(versionOfArtifactIn(packageArtifactAsJar(tempDir, "Implementation-Version: 3.4.5")))
                .isEqualTo("3.4.5");
    }

    /**
     * A jar manifest that records only a specification version, and no implementation version, reports the
     * specification version.
     *
     * @param tempDir
     *            a temporary directory to build the artifact in.
     * @throws Exception
     *             if the artifact could not be built or read.
     */
    @Test
    public void theSpecificationVersionIsUsedIfAJarManifestHasNoImplementationVersion(@TempDir final Path tempDir)
            throws Exception {
        buildArtifact(tempDir, /* pomXmlContent = */ null);
        assertThat(versionOfArtifactIn(packageArtifactAsJar(tempDir, "Specification-Version: 6.7")))
                .isEqualTo("6.7");
    }

    /**
     * The version of the classloading code itself is found, since it is built by Maven and run from a build
     * directory during the build.
     */
    @Test
    public void theVersionOfClassGraphsOwnArtifactIsFound() {
        assertThat(VersionFinder.getVersion(VersionFinder.class, "io.github.classgraph", "classgraph-base"))
                .isNotEqualTo("unknown").matches("\\d+\\.\\d+\\.\\d+.*");
    }

    // -----------------------------------------------------------------------------------------------------------

    /** A system property that is set is returned, and one that is not set is reported as absent. */
    @Test
    public void aSystemPropertyThatIsNotSetIsReportedAsAbsent() {
        assertThat(VersionFinder.getProperty("java.version")).isNotNull();
        assertThat(VersionFinder.getProperty("io.github.classgraph.no.such.property")).isNull();
    }

    /** The default value is used for a system property that is not set, but not for one that is. */
    @Test
    public void theDefaultValueIsUsedOnlyForASystemPropertyThatIsNotSet() {
        assertThat(VersionFinder.getProperty("io.github.classgraph.no.such.property", "the-default"))
                .isEqualTo("the-default");
        assertThat(VersionFinder.getProperty("java.version", "the-default")).isNotEqualTo("the-default");
    }

    // -----------------------------------------------------------------------------------------------------------

    /** The operating system is identified, and is identified as Windows exactly when paths are Windows paths. */
    @Test
    public void theOperatingSystemIsIdentified() {
        assertThat(VersionFinder.OS).isNotNull().isNotEqualTo(VersionFinder.OperatingSystem.Unknown);
        assertThat(VersionFinder.OS == VersionFinder.OperatingSystem.Windows).isEqualTo(File.separatorChar == '\\');
    }

    /** The Java version is the major version, and is at least the oldest version ClassGraph supports. */
    @Test
    public void theJavaMajorVersionIsFound() {
        assertThat(VersionFinder.JAVA_MAJOR_VERSION).isGreaterThanOrEqualTo(17);
    }
}
