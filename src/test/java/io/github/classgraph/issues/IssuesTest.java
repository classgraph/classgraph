package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalSuperclass;
import io.github.classgraph.test.internal.InternalExtendsExternal;
import io.github.classgraph.test.whitelisted.Impl1;
import io.github.classgraph.test.whitelisted.Impl1Sub;

/**
 * IssuesTest.
 */
public class IssuesTest {
    /**
     * Issue 70.
     */
    @Test
    public void issue70() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
        }
    }

    /**
     * Issue 70 enable external classes.
     */
    @Test
    public void issue70EnableExternalClasses() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
            assertThat(scanResult.getSuperclasses(Impl1Sub.class.getName()).getNames())
                    .containsOnly(Impl1.class.getName());
        }
    }

    /**
     * Extends external.
     */
    @Test
    public void extendsExternal() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsOnly(ExternalSuperclass.class.getName());
        }
    }

    /**
     * Extends external with enable external.
     */
    @Test
    public void extendsExternalWithEnableExternal() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsOnly(ExternalSuperclass.class.getName());
        }
    }

    /**
     * Extends external subclass.
     */
    @Test
    public void extendsExternalSubclass() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsOnly(InternalExtendsExternal.class.getName());
        }
    }

    /**
     * Non strict extends external subclass.
     */
    @Test
    public void nonStrictExtendsExternalSubclass() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsOnly(InternalExtendsExternal.class.getName());
        }
    }
}
