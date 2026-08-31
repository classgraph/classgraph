package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * MultiReleaseJar.
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
    public void multiReleaseJar() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo("mrj.Cls");
            assertThat(classInfo).isNotNull();
            final var classfileResource = classInfo.getResource();
            assertThat(classfileResource).isNotNull();
            // The class is reported under its unversioned path, but the classfile that was read is the JDK 9
            // version of the class, not the base version
            assertThat(classfileResource.getPath()).isEqualTo("mrj/Cls.class");
            assertThat(classfileResource.getPathRelativeToContainer())
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
     * @throws Exception
     *             the exception
     */
    @Test
    public void multiReleaseVersioningOfResources() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths("nonexistent_path")
                        .scan()) {
            assertThat(scanResult.getResourcesWithPath("mrj/Cls.class")).isEmpty();
            assertThat(scanResult.getResourcesWithPathIgnoringAccept("mrj/Cls.class")).isNotEmpty();
        }
    }

    /**
     * Loading all versions of multi release class and text resources with `disableMultiReleaseVersions`.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void disableMultiReleaseVersions() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).disableMultiReleaseVersions()
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
     * `disableMultiReleaseVersions` still allows classfiles to be scanned, and reports the classes that a JVM too
     * old to know about multi-release jarfiles would load: the base version of the class, and none of the versioned
     * copies, which are stored beneath {@code META-INF/versions/<N>/}, where no class could be loaded from.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void disableMultiReleaseVersionsWithClassInfo() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[] { jarURL });
                var scanResult = new ClassGraph().enableClassLoaders(classLoader).enableAllInfo()
                        .disableMultiReleaseVersions().scan()) {
            // Both copies of the classfile are listed as resources, each under the path it is stored under
            assertThat(scanResult.getResourcesWithPath("mrj/Cls.class")).hasSize(1);
            assertThat(scanResult.getResourcesWithPath("META-INF/versions/9/mrj/Cls.class")).hasSize(1);

            // Only the base copy is scanned as a classfile, so the class is reported exactly once, and the
            // versioned copy is not reported as a class of a package named after the directories it is stored in
            final var classInfo = scanResult.getClassInfo("mrj.Cls");
            assertThat(classInfo).isNotNull();
            final var classfileResource = classInfo.getResource();
            assertThat(classfileResource).isNotNull();
            assertThat(classfileResource.getPathRelativeToContainer()).isEqualTo("mrj/Cls.class");
            assertThat(scanResult.getAllClasses().getNames()).contains("mrj.Cls")
                    .doesNotContain("META-INF.versions.9.mrj.Cls");

            // The classfile that was parsed is the base copy, not the JDK 9 copy, whose content differs
            assertThat(classfileResource.load())
                    .isEqualTo(scanResult.getResourcesWithPath("mrj/Cls.class").get(0).load()).isNotEqualTo(
                            scanResult.getResourcesWithPath("META-INF/versions/9/mrj/Cls.class").get(0).load());
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
     * `disableMultiReleaseVersions` does not turn off any of the features `enableAllInfo` turns on, whichever order
     * the two are called in.
     */
    @Test
    public void enableAllInfoAfterDisableMultiReleaseVersions() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(MultiReleaseJarTest.class.getPackage().getName()).disableMultiReleaseVersions()
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(ClassRetained.class).getNames())
                    .containsOnly(ClassRetainedAnnotated.class.getName());
        }
    }

    /**
     * `enableAllInfo` before `disableMultiReleaseVersions` leaves class info enabled too -- the two settings are
     * independent, so neither order silently turns the other off.
     */
    @Test
    public void disableMultiReleaseVersionsAfterEnableAllInfo() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(MultiReleaseJarTest.class.getPackage().getName()).enableAllInfo()
                .disableMultiReleaseVersions().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(ClassRetained.class).getNames())
                    .containsOnly(ClassRetainedAnnotated.class.getName());
        }
    }
}
