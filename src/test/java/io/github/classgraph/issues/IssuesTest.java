package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.test.external.ExternalSuperclass;
import io.github.classgraph.test.internal.InternalExtendsExternal;
import io.github.classgraph.test.whitelisted.Impl1;
import io.github.classgraph.test.whitelisted.Impl1Sub;

public class IssuesTest {
    @Test
    public void testIssuesScan() {
        new ClassGraph().whitelistPackages("io.github.classgraph.issues").scan();
    }

    @Test
    public void issue70() {
        assertThat(new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName()).scan()
                .getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
    }

    @Test
    public void issue70EnableExternalClasses() {
        assertThat(new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName()).enableExternalClasses()
                .scan().getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
        assertThat(new ClassGraph().whitelistPackages(Impl1.class.getPackage().getName()).enableExternalClasses()
                .scan().getSuperclasses(Impl1Sub.class.getName()).getNames()).containsOnly(Impl1.class.getName());
    }

    @Test
    public void extendsExternal() {
        assertThat(new ClassGraph().whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                        .containsOnly(ExternalSuperclass.class.getName());
    }

    @Test
    public void nonStrictExtendsExternal() {
        assertThat(new ClassGraph().whitelistPackages(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan().getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                        .containsOnly(ExternalSuperclass.class.getName());
    }

    @Test
    public void extendsExternalSubclass() {
        assertThat(new ClassGraph().whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getSubclasses(ExternalSuperclass.class.getName()).getNames())
                        .containsOnly(InternalExtendsExternal.class.getName());
    }

    @Test
    public void nonStrictExtendsExternalSubclass() {
        assertThat(new ClassGraph().whitelistPackages(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan().getSubclasses(ExternalSuperclass.class.getName()).getNames())
                        .containsOnly(InternalExtendsExternal.class.getName());
    }
}
