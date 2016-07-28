package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Named;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue38.ImplementsNamed;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1;
import io.github.lukehutch.fastclasspathscanner.test.whitelisted.Impl1Sub;

public class IssuesTest {
    @Test
    public void testIssuesScan() {
        new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner.issues").scan();
    }

    @Test
    public void testImplementsNamed() {
        assertThat(new FastClasspathScanner("").scan().getNamesOfClassesImplementing(Named.class))
                .contains(ImplementsNamed.class.getName());
    }

    @Test
    public void issue70() {
        assertThat(new FastClasspathScanner("").scan().getNamesOfSubclassesOf("java.lang.Object"))
                .doesNotContain(Impl1.class.getName());
        assertThat(new FastClasspathScanner("!").verbose().scan().getNamesOfSubclassesOf("java.lang.Object"))
                .contains(Impl1Sub.class.getName());
        assertThat(new FastClasspathScanner("!").scan().getNamesOfSubclassesOf("java.lang.Object"))
                .doesNotContain(Impl1Sub.class.getName());
    }
}
