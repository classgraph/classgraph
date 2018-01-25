package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Named;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue38.ImplementsNamed;
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
    public void testImplementsNamed() {
        assertThat(new FastClasspathScanner().scan().getNamesOfClassesImplementing(Named.class))
                .contains(ImplementsNamed.class.getName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testImplementsNamedStrict() {
        new FastClasspathScanner().strictWhitelist().scan().getNamesOfClassesImplementing(Named.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void issue70Strict() {
        assertThat(new FastClasspathScanner().strictWhitelist().scan().getNamesOfSubclassesOf(Object.class))
                .doesNotContain(Impl1.class.getName());
    }

    @Test
    public void issue70NonStrict() {
        assertThat(new FastClasspathScanner().scan().getNamesOfSubclassesOf(Object.class))
                .contains(Impl1.class.getName());
        assertThat(new FastClasspathScanner().scan().getNamesOfSuperclassesOf(Impl1Sub.class))
                .doesNotContain(Object.class.getName());
        assertThat(new FastClasspathScanner("!").scan().getNamesOfSuperclassesOf(Impl1Sub.class))
                .contains(Object.class.getName());
    }

    @Test
    public void strictExtendsExternal() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).strictWhitelist()
                .scan().getNamesOfSuperclassesOf(InternalExtendsExternal.class)).isEmpty();
    }

    @Test
    public void nonStrictExtendsExternal() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getNamesOfSuperclassesOf(InternalExtendsExternal.class))
                        .containsExactly(ExternalSuperclass.class.getName());
    }

    @Test
    public void strictExtendsExternalSubclass() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).strictWhitelist()
                .scan().getNamesOfSubclassesOf(ExternalSuperclass.class))
                        .containsExactly(InternalExtendsExternal.class.getName());
    }

    @Test
    public void nonStrictExtendsExternalSubclass() {
        assertThat(new FastClasspathScanner(InternalExtendsExternal.class.getPackage().getName()).scan()
                .getNamesOfSubclassesOf(ExternalSuperclass.class))
                        .containsExactly(InternalExtendsExternal.class.getName());
    }
}
