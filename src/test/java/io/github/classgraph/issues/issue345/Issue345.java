package io.github.classgraph.issues.issue345;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * Issue345.
 */
public class Issue345 {
    /**
     * Superclass.
     */
    private static class Super {
    }

    /**
     * Subclass.
     */
    public static class Sub extends Super {
    }

    /**
     * Test that private superclasses have their {@link Resource} reference set with .ignoreClassVisibility().
     */
    @Test
    public void withIgnoreClassVisibility() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Super.class.getName(), Sub.class.getName())
                .ignoreClassVisibility().scan()) {
            final ClassInfo subClassInfo = scanResult.getClassInfo(Sub.class.getName());
            assertThat(subClassInfo).isNotNull();
            assertThat(subClassInfo.getResource()).isNotNull();
            final ClassInfo superClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(superClassInfo).isNotNull();
            assertThat(superClassInfo.getResource()).isNotNull();
        }
    }

    /**
     * Test that private superclasses do not have their {@link Resource} reference set without
     * .ignoreClassVisibility().
     */
    @Test
    public void withoutIgnoreClassVisibility() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Super.class.getName(), Sub.class.getName())
                .scan()) {
            final ClassInfo subClassInfo = scanResult.getClassInfo(Sub.class.getName());
            assertThat(subClassInfo).isNotNull();
            assertThat(subClassInfo.getResource()).isNotNull();
            final ClassInfo superClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(superClassInfo).isNotNull();
            assertThat(superClassInfo.getResource()).isNull();
        }
    }

    /**
     * Test that extending scanning to superclasses causes the {@link Resource} reference to be set.
     */
    @Test
    public void testExtensionToParent() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Sub.class.getName()).ignoreClassVisibility()
                .scan()) {
            final ClassInfo superClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(superClassInfo).isNotNull();
            assertThat(superClassInfo.getResource()).isNotNull();
        }
    }

    /**
     * Test that extending scanning to outer class causes the {@link Resource} reference to be set.
     */
    @Test
    public void testExtensionToOuterClass() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Super.class.getName())
                .ignoreClassVisibility().scan()) {
            final ClassInfo outerClassInfo = scanResult.getClassInfo(Issue345.class.getName());
            assertThat(outerClassInfo).isNotNull();
            assertThat(outerClassInfo.getResource()).isNotNull();
        }
    }

    /**
     * Test that scanning is not extended to inner class, because the {@link Resource} reference is not set.
     */
    @Test
    public void testNonExtensionToInnerClass() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Issue345.class.getName())
                .ignoreClassVisibility().scan()) {
            final ClassInfo innerClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(innerClassInfo).isNotNull();
            assertThat(innerClassInfo.getResource()).isNull();
        }
    }

    /**
     * Test that overriding classloaders does not allow other classloaders to be scanned.
     */
    @Test
    public void issue345b() throws Exception {
        // Find URL of this class' classpath element
        URL classpathURL;
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Issue345.class.getName()).scan()) {
            classpathURL = scanResult.getClassInfo(Issue345.class.getName()).getClasspathElementURL();
        }
        // Use this to create an override URLClassLoader
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .overrideClassLoaders(new URLClassLoader(new URL[] { classpathURL })).ignoreParentClassLoaders()
                .verbose().scan()) {
            // Assert that this class is found in its own classloader
            assertThat(scanResult.getClassInfo(Issue345.class.getName())).isNotNull();
            // But that other classpath elements on the classpath are not found
            assertThat(scanResult.getClassInfo(Test.class.getName())).isNull();
        }
    }
}
