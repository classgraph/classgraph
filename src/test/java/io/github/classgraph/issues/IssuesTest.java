package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalSuperclass;
import io.github.classgraph.test.internal.InternalExtendsExternal;
import io.github.classgraph.test.whitelisted.Impl1;
import io.github.classgraph.test.whitelisted.Impl1Sub;

public class IssuesTest {
    @Test
    public void issue70() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
        }
    }

    @Test
    public void issue70EnableExternalClasses() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName())
                .enableExternalClasses().scan()) {
            assertThat(scanResult.getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
            assertThat(scanResult.getSuperclasses(Impl1Sub.class.getName()).getNames())
                    .containsExactlyInAnyOrder(Impl1.class.getName());
        }
    }

    @Test
    public void extendsExternal() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsExactlyInAnyOrder(ExternalSuperclass.class.getName());
        }
    }

    @Test
    public void extendsExternalWithEnableExternal() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                    .containsExactlyInAnyOrder(ExternalSuperclass.class.getName());
        }
    }

    @Test
    public void extendsExternalSubclass() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalExtendsExternal.class.getName());
        }
    }

    @Test
    public void nonStrictExtendsExternalSubclass() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan()) {
            assertThat(scanResult.getSubclasses(ExternalSuperclass.class.getName()).getNames())
                    .containsExactlyInAnyOrder(InternalExtendsExternal.class.getName());
        }
    }
}
