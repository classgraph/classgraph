package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

/**
 * MultiReleaseJar. (Multi-release jar sections are ignored by ClassGraph on JDK 8 and below, so these tests only
 * run on JDK 9+.)
 */
public class MultiReleaseJarTest {
    /** The jar URL. */
    private static final URL jarURL = MultiReleaseJarTest.class.getClassLoader()
            .getResource("multi-release-jar.jar");

    /**
     * Multi release jar.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void multiReleaseJar() throws Exception {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL })).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo("mrj.Cls");
            assertThat(classInfo).isNotNull();
            final Class<?> cls = classInfo.loadClass();
            final Method getVersionStatic = cls.getMethod("getVersionStatic");
            getVersionStatic.setAccessible(true);
            assertThat(getVersionStatic.invoke(null)).isEqualTo(9);
            final Constructor<?> constructor = cls.getConstructor();
            constructor.setAccessible(true);
            assertThat(constructor).isNotNull();
            final Object clsInstance = constructor.newInstance();
            assertThat(clsInstance).isNotNull();
            final Method getVersion = cls.getMethod("getVersion");
            getVersion.setAccessible(true);
            assertThat(getVersion.invoke(clsInstance)).isEqualTo(9);

            final ResourceList resources = scanResult.getResourcesWithPath("resource.txt");
            assertThat(resources.size()).isEqualTo(1);
            resources.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("9"));
        }
    }

    /**
     * Multi release versioning of resources.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void multiReleaseVersioningOfResources() throws Exception {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL })).acceptPaths("nonexistent_path")
                .scan()) {
            assertThat(scanResult.getResourcesWithPath("mrj/Cls.class")).isEmpty();
            assertThat(scanResult.getResourcesWithPathIgnoringAccept("mrj/Cls.class")).isNotEmpty();
        }
    }

    /**
     * A jarfile is only a multi-release jarfile if its manifest has the `Multi-Release` key. In one that does not
     * have it, an entry stored beneath `META-INF/versions/` overrides nothing: the JVM reads the base entry for the
     * path the version prefix would have named, so a scan has to report the base entry too.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile in.
     * @throws Exception
     *             the exception
     */
    @Test
    public void versionedEntryInAJarThatIsNotMultiReleaseOverridesNothing(@TempDir final Path tempDir)
            throws Exception {
        final Path jar = tempDir.resolve("not-multi-release.jar");
        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            // No "Multi-Release" key, so this is not a multi-release jarfile
            writeEntry(jarOut, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n");
            // The versioned entry is written before the base entry, so that a scan that reports the base entry
            // cannot be doing so just by taking the first entry with a given name
            writeEntry(jarOut, "META-INF/versions/9/res.txt", "9");
            writeEntry(jarOut, "res.txt", "base");
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath("res.txt");
            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).getPathRelativeToClasspathElement()).isEqualTo("res.txt");
            assertThat(new String(resources.get(0).load(), StandardCharsets.UTF_8)).isEqualTo("base");
        }
    }

    /**
     * Write one entry to a jarfile.
     *
     * @param jarOut
     *            the jarfile to write to.
     * @param entryName
     *            the name to store the entry under.
     * @param content
     *            the content of the entry.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void writeEntry(final JarOutputStream jarOut, final String entryName, final String content)
            throws IOException {
        jarOut.putNextEntry(new JarEntry(entryName));
        jarOut.write(content.getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
    }

    /**
     * Loading all versions of multi release class and text resources with `enableMultiReleaseVersions`.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void enableMultiReleaseVersions() throws Exception {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL })).enableMultiReleaseVersions()
                .scan()) {
            final ResourceList java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            final ResourceList java9ClassResource = scanResult
                    .getResourcesWithPath("META-INF/versions/9/mrj/Cls.class");
            assertThat(java9ClassResource).hasSize(1);
            assertThat(java8ClassResource.get(0).load()).isNotEqualTo(java9ClassResource.get(0).load());

            final ResourceList java8Resource = scanResult.getResourcesWithPath("resource.txt");
            assertThat(java8Resource.size()).isEqualTo(1);
            java8Resource.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("8"));
            final ResourceList java9Resource = scanResult.getResourcesWithPath("META-INF/versions/9/resource.txt");
            assertThat(java9Resource.size()).isEqualTo(1);
            java9Resource.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("9"));
        }
    }

    /**
     * `enableMultiReleaseVersions` does not make sense with class info and should disable it.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void enableMultiReleaseVersionsWithClassInfo() throws Exception {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL })).enableAllInfo()
                .enableMultiReleaseVersions().scan()) {
            final ResourceList java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            assertThatThrownBy(() -> scanResult.getClassInfo("mrj.Cls"))
                    .isInstanceOfAny(IllegalArgumentException.class);
        }
    }

    /** A CLASS-retained, and therefore runtime-invisible, annotation. */
    @Retention(RetentionPolicy.CLASS)
    public @interface ClassRetained {
    }

    /** A class annotated with a runtime-invisible annotation. */
    @ClassRetained
    public static class ClassRetainedAnnotated {
    }

    /**
     * `enableMultiReleaseVersions` and `enableAllInfo` cancel each other out, whichever order they are called in,
     * so calling `enableAllInfo` last must not leave runtime-invisible annotations hidden.
     */
    @Test
    public void enableAllInfoAfterEnableMultiReleaseVersions() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(MultiReleaseJarTest.class.getPackage().getName()).enableMultiReleaseVersions()
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(ClassRetained.class).getNames())
                    .containsOnly(ClassRetainedAnnotated.class.getName());
        }
    }
}
