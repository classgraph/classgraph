package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ResourceList.ByteArrayConsumer;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.utils.VersionFinder;

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
        if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
            // Multi-release jar sections are ignored by ClassGraph if JDK<9 
            System.out.println("Skipping multi-release jar test, as JDK version " + VersionFinder.JAVA_VERSION
                    + " is less than 9");
        } else {
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
                resources.forEachByteArray(new ByteArrayConsumer() {
                    @Override
                    public void accept(final Resource resource, final byte[] byteArray) {
                        assertThat(new String(byteArray).trim()).isEqualTo("9");
                    }
                });
            }
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
        // Multi-release jar sections are ignored by ClassGraph if JDK<9 
        if (VersionFinder.JAVA_MAJOR_VERSION >= 9) {
            try (ScanResult scanResult = new ClassGraph()
                    .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                    .whitelistPaths("nonexistent_path").scan()) {
                assertThat(scanResult.getResourcesWithPath("mrj/Cls.class")).isEmpty();
                assertThat(scanResult.getResourcesWithPathIgnoringWhitelist("mrj/Cls.class")).isNotEmpty();
            }
        }
    }
}
