package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * MultiReleaseJar.
 */
public class MultiReleaseJarTest {
    /** The jar URL. */
    private static final URL jarURL = MultiReleaseJarTest.class.getClassLoader().getResource("multi-release-jar.jar");

    /**
     * Multi release jar.
     *
     * @throws Exception the exception
     */
    @Test
    public void multiReleaseJar() throws Exception {
        try (var scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo("mrj.Cls");
            assertThat(classInfo).isNotNull();
            final var cls = classInfo.loadClass();
            final var getVersionStatic = cls.getMethod("getVersionStatic");
            getVersionStatic.setAccessible(true);
            assertThat(getVersionStatic.invoke(null)).isEqualTo(9);
            final var constructor = cls.getConstructor();
            constructor.setAccessible(true);
            assertThat(constructor).isNotNull();
            final var clsInstance = constructor.newInstance();
            assertThat(clsInstance).isNotNull();
            final var getVersion = cls.getMethod("getVersion");
            getVersion.setAccessible(true);
            assertThat(getVersion.invoke(clsInstance)).isEqualTo(9);

            final var resources = scanResult.getResourcesWithPath("resource.txt");
            assertThat(resources.size()).isEqualTo(1);
            resources.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("9"));
        }
    }

    /**
     * Multi release versioning of resources.
     *
     * @throws Exception the exception
     */
    @Test
    public void multiReleaseVersioningOfResources() throws Exception {
        try (var scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .acceptPaths("nonexistent_path").scan()) {
            assertThat(scanResult.getResourcesWithPath("mrj/Cls.class")).isEmpty();
            assertThat(scanResult.getResourcesWithPathIgnoringAccept("mrj/Cls.class")).isNotEmpty();
        }
    }

    /**
     * Loading all versions of multi release class and text resources with
     * `enableMultiReleaseVersions`.
     *
     * @throws Exception the exception
     */
    @Test
    public void enableMultiReleaseVersions() throws Exception {
        try (var scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .enableMultiReleaseVersions().scan()) {
            final var java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            final var java9ClassResource = scanResult.getResourcesWithPath("META-INF/versions/9/mrj/Cls.class");
            assertThat(java9ClassResource).hasSize(1);
            assertThat(java8ClassResource.get(0).load()).isNotEqualTo(java9ClassResource.get(0).load());

            final var java8Resource = scanResult.getResourcesWithPath("resource.txt");
            assertThat(java8Resource.size()).isEqualTo(1);
            java8Resource.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("8"));
            final var java9Resource = scanResult.getResourcesWithPath("META-INF/versions/9/resource.txt");
            assertThat(java9Resource.size()).isEqualTo(1);
            java9Resource.forEachByteArrayThrowingIOException(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("9"));
        }
    }

    /**
     * `enableMultiReleaseVersions` does not make sense with class info and should
     * disable it.
     *
     * @throws Exception the exception
     */
    @Test
    public void enableMultiReleaseVersionsWithClassInfo() throws Exception {
        try (var scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .enableAllInfo().enableMultiReleaseVersions().scan()) {
            final var java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            assertThatThrownBy(() -> scanResult.getClassInfo("mrj.Cls"))
                    .isInstanceOfAny(IllegalArgumentException.class);
        }
    }
}
