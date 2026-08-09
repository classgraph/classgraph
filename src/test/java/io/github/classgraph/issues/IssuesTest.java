package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.test.accepted.Impl1;
import io.github.classgraph.test.accepted.Impl1Sub;
import io.github.classgraph.test.external.ExternalSuperclass;
import io.github.classgraph.test.internal.InternalExtendsExternal;

/**
 * IssuesTest.
 */
public class IssuesTest {
    @Test
    public void issue70() {
        try (var scanResult = new ClassGraph().acceptPackages(Impl1.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getAllSubclasses(Object.class).getNames()).contains(Impl1.class.getName());
        }
    }

    @Test
    public void issue70EnableExternalClasses() {
        try (var scanResult = new ClassGraph().acceptPackages(Impl1.class.getPackage().getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAllSubclasses(Object.class).getNames()).contains(Impl1.class.getName());
            assertThat(scanResult.getAllSuperclasses(Impl1Sub.class.getName()).getNames())
                    .containsOnly(Impl1.class.getName(), "java.lang.Object");
        }
    }

    /**
     * Extends external.
     */
    @Test
    public void extendsExternal() {
        try (var scanResult = new ClassGraph().acceptPackages(InternalExtendsExternal.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getAllSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsOnly(ExternalSuperclass.class.getName(), "java.lang.Object");
        }
    }

    /**
     * Extends external with enable external.
     */
    @Test
    public void extendsExternalWithEnableExternal() {
        try (var scanResult = new ClassGraph().acceptPackages(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAllSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsOnly(ExternalSuperclass.class.getName(), "java.lang.Object");
        }
    }

    /**
     * Extends external subclass.
     */
    @Test
    public void extendsExternalSubclass() {
        try (var scanResult = new ClassGraph().acceptPackages(InternalExtendsExternal.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getAllSubclasses(ExternalSuperclass.class).getNames())
                    .containsOnly(InternalExtendsExternal.class.getName());
        }
    }

    /**
     * Non strict extends external subclass.
     */
    @Test
    public void nonStrictExtendsExternalSubclass() {
        try (var scanResult = new ClassGraph().acceptPackages(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getAllSubclasses(ExternalSuperclass.class).getNames())
                    .containsOnly(InternalExtendsExternal.class.getName());
        }
    }
}
