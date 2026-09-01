package io.github.classgraph.issues.issue345;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;

public class Issue345Test {
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
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Super.class.getName(), Sub.class.getName()).ignoreClassVisibility().scan()) {
            final var subClassInfo = scanResult.getClassInfo(Sub.class.getName());
            assertThat(subClassInfo).isNotNull();
            assertThat(subClassInfo.getResource()).isNotNull();
            final var superClassInfo = scanResult.getClassInfo(Super.class.getName());
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
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Super.class.getName(), Sub.class.getName()).scan()) {
            final var subClassInfo = scanResult.getClassInfo(Sub.class.getName());
            assertThat(subClassInfo).isNotNull();
            assertThat(subClassInfo.getResource()).isNotNull();
            final var superClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(superClassInfo).isNotNull();
            assertThat(superClassInfo.getResource()).isNull();
        }
    }

    /**
     * Test that extending scanning to superclasses causes the {@link Resource} reference to be set.
     */
    @Test
    public void testExtensionToParent() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Sub.class.getName())
                .ignoreClassVisibility().scan()) {
            final var superClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(superClassInfo).isNotNull();
            assertThat(superClassInfo.getResource()).isNotNull();
        }
    }

    /**
     * Test that extending scanning to outer class causes the {@link Resource} reference to be set.
     */
    @Test
    public void testExtensionToOuterClass() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Super.class.getName())
                .ignoreClassVisibility().scan()) {
            final var outerClassInfo = scanResult.getClassInfo(Issue345Test.class.getName());
            assertThat(outerClassInfo).isNotNull();
            assertThat(outerClassInfo.getResource()).isNotNull();
        }
    }

    /**
     * Test that scanning is not extended to inner class, because the {@link Resource} reference is not set.
     */
    @Test
    public void testNonExtensionToInnerClass() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Issue345Test.class.getName())
                .ignoreClassVisibility().scan()) {
            final var innerClassInfo = scanResult.getClassInfo(Super.class.getName());
            assertThat(innerClassInfo).isNotNull();
            assertThat(innerClassInfo.getResource()).isNull();
        }
    }

    /**
     * Test that overriding classloaders does not allow other classloaders to be scanned.
     */
    @Test
    public void issue345b() {
        // Find URL of this class' classpath element
        URL classpathURL;
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Issue345Test.class.getName()).scan()) {
            classpathURL = scanResult.getClassInfo(Issue345Test.class.getName()).getClasspathElementURL();
        }
        // Use this to create an override URLClassLoader
        try (var scanResult = new ClassGraph().enableClassInfo()
                .enableClassLoaders(new URLClassLoader(new URL[] { classpathURL })).ignoreParentClassLoaders()
                .scan()) {
            // Assert that this class is found in its own classloader
            assertThat(scanResult.getClassInfo(Issue345Test.class.getName())).isNotNull();
            // But that other classpath elements on the classpath are not found
            assertThat(scanResult.getClassInfo(Test.class.getName())).isNull();
        }
    }

    /**
     * A.
     */
    private static class A {
    }

    /**
     * B.
     */
    abstract static class B extends A {
    }

    /**
     * C.
     */
    public static class C extends B {
    }

    /**
     * Test inner class modifiers are picked up from the InnerClasses attribute of classfiles.
     */
    @Test
    public void issue345c() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo()
                .acceptPackages(Issue345Test.class.getPackage().getName()).ignoreClassVisibility().scan()) {
            final var ciA = scanResult.getClassInfo(A.class.getName());
            assertThat(ciA.getModifiersString()).isEqualTo("private static");
            final var ciB = scanResult.getClassInfo(B.class.getName());
            assertThat(ciB.getModifiersString()).isEqualTo("abstract static");
            final var ciC = scanResult.getClassInfo(C.class.getName());
            assertThat(ciC.getModifiersString()).isEqualTo("public static");
        }
    }
}
