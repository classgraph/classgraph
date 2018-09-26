package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ResourceList.ByteArrayConsumer;
import io.github.classgraph.ScanResult;
import io.github.classgraph.utils.VersionFinder;

public class MultiReleaseJar {
    @Test
    public void multiReleaseJar() {
        final URL jarURL = MultiReleaseJar.class.getClassLoader().getResource("multi-release-jar.jar");

        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }))
                .enableAllInfo().scan()) {
            if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
                System.out.println("Skipping test, as JDK version is less than 9");
            } else {
                try {
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
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
