package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalSuperclass;
import io.github.lukehutch.fastclasspathscanner.test.internal.InternalExtendsExternal;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1Sub;

public class IssuesTest {
    @Test
    public void testIssuesScan() {
        new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner.issues").scan();
    }

    @Test
    public void issue70() {
        assertThat(new FastClasspathScanner(Impl1.class.getPackage().getName()).scan()
                .getNamesOfSubclassesOf(Object.class)).contains(Impl1.class.getName());
    }

    @Test
    public void issue70EnableExternalClasses() {
        assertThat(new FastClasspathScanner(Impl1.class.getPackage().getName()).enableExternalClasses().scan()
                .getNamesOfSubclassesOf(Object.class)).contains(Impl1.class.getName());
        assertThat(new FastClasspathScanner(Impl1.class.getPackage().getName()).enableExternalClasses().scan()
                .getNamesOfSuperclassesOf(Impl1Sub.class)).doesNotContain(Object.class.getName());
        assertThat(new FastClasspathScanner("!").enableExternalClasses().scan()
                .getNamesOfSuperclassesOf(Impl1Sub.class)).contains(Object.class.getName());
    }

    @Test
    public void extendsExternal() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getNamesOfSuperclassesOf(InternalExtendsExternal.class)).isEmpty();
    }

    @Test
    public void nonStrictExtendsExternal() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan().getNamesOfSuperclassesOf(InternalExtendsExternal.class))
                        .containsExactly(ExternalSuperclass.class.getName());
    }

    @Test
    public void extendsExternalSubclass() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getNamesOfSubclassesOf(ExternalSuperclass.class))
                        .containsExactly(InternalExtendsExternal.class.getName());
    }

    @Test
    public void nonStrictExtendsExternalSubclass() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan().getNamesOfSubclassesOf(ExternalSuperclass.class))
                        .containsExactly(InternalExtendsExternal.class.getName());
    }
}
