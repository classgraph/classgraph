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
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().overrideClassLoaders(classLoader).enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo("mrj.Cls");
            assertThat(classInfo).isNotNull();
            final var classfileResource = classInfo.getResource();
            assertThat(classfileResource).isNotNull();
            // The class is reported under its unversioned path, but the classfile that
            // was read is the JDK 9 version of the class, not the base version
            assertThat(classfileResource.getPath()).isEqualTo("mrj/Cls.class");
            assertThat(classfileResource.getPathRelativeToClasspathElement())
                    .isEqualTo("META-INF/versions/9/mrj/Cls.class");
            assertThat(classInfo.getMethodInfo("getVersionStatic").get(0).isStatic()).isTrue();
            assertThat(classInfo.getMethodInfo("getVersion").get(0).isStatic()).isFalse();
            assertThat(classInfo.getConstructorInfo()).isNotEmpty();

            final var resources = scanResult.getResourcesWithPath("resource.txt");
            assertThat(resources.size()).isEqualTo(1);
            resources.forEachByteArray(
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
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().overrideClassLoaders(classLoader).acceptPaths("nonexistent_path")
                        .scan()) {
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
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().overrideClassLoaders(classLoader).enableMultiReleaseVersions()
                        .scan()) {
            final var java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            final var java9ClassResource = scanResult.getResourcesWithPath("META-INF/versions/9/mrj/Cls.class");
            assertThat(java9ClassResource).hasSize(1);
            assertThat(java8ClassResource.get(0).load()).isNotEqualTo(java9ClassResource.get(0).load());

            final var java8Resource = scanResult.getResourcesWithPath("resource.txt");
            assertThat(java8Resource.size()).isEqualTo(1);
            java8Resource.forEachByteArray(
                    (resource, byteArray) -> assertThat(new String(byteArray).trim()).isEqualTo("8"));
            final var java9Resource = scanResult.getResourcesWithPath("META-INF/versions/9/resource.txt");
            assertThat(java9Resource.size()).isEqualTo(1);
            java9Resource.forEachByteArray(
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
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().overrideClassLoaders(classLoader).enableAllInfo()
                        .enableMultiReleaseVersions().scan()) {
            final var java8ClassResource = scanResult.getResourcesWithPath("mrj/Cls.class");
            assertThat(java8ClassResource).hasSize(1);
            assertThatThrownBy(() -> scanResult.getClassInfo("mrj.Cls"))
                    .isInstanceOfAny(IllegalStateException.class);
        }
    }
}
