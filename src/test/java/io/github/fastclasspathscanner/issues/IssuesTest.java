package io.github.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.fastclasspathscanner.FastClasspathScanner;
import io.github.fastclasspathscanner.test.external.ExternalSuperclass;
import io.github.fastclasspathscanner.test.internal.InternalExtendsExternal;
import io.github.fastclasspathscanner.test.whitelisted.Impl1;
import io.github.fastclasspathscanner.test.whitelisted.Impl1Sub;

public class IssuesTest {
    @Test
    public void testIssuesScan() {
        new FastClasspathScanner().whitelistPackages("io.github.fastclasspathscanner.issues").scan();
    }

    @Test
    public void issue70() {
        assertThat(new FastClasspathScanner().whitelistPackages(Impl1.class.getPackage().getName()).scan()
                .getSubclasses(Object.class.getName()).getNames()).contains(Impl1.class.getName());
    }

    @Test
    public void issue70EnableExternalClasses() {
        assertThat(new FastClasspathScanner().whitelistPackages(Impl1.class.getPackage().getName())
                .enableExternalClasses().scan().getSubclasses(Object.class.getName()).getNames())
                        .contains(Impl1.class.getName());
        assertThat(new FastClasspathScanner().whitelistPackages(Impl1.class.getPackage().getName())
                .enableExternalClasses().scan().getSuperclasses(Impl1Sub.class.getName()).getNames())
                        .containsOnly(Impl1.class.getName());
    }

    @Test
    public void extendsExternal() {
        assertThat(
                new FastClasspathScanner().whitelistPackages(InternalExtendsExternal.class.getPackage().getName())
                        .scan().getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                                .containsOnly(ExternalSuperclass.class.getName());
    }

    @Test
    public void nonStrictExtendsExternal() {
        assertThat(new FastClasspathScanner()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan().getSuperclasses(InternalExtendsExternal.class.getName()).getNames())
                        .containsOnly(ExternalSuperclass.class.getName());
    }

    @Test
    public void extendsExternalSubclass() {
        assertThat(
                new FastClasspathScanner().whitelistPackages(InternalExtendsExternal.class.getPackage().getName())
                        .scan().getSubclasses(ExternalSuperclass.class.getName()).getNames())
                                .containsOnly(InternalExtendsExternal.class.getName());
    }

    @Test
    public void nonStrictExtendsExternalSubclass() {
        assertThat(new FastClasspathScanner()
                .whitelistPackages(InternalExtendsExternal.class.getPackage().getName()).enableExternalClasses()
                .scan().getSubclasses(ExternalSuperclass.class.getName()).getNames())
                        .containsOnly(InternalExtendsExternal.class.getName());
    }
}
