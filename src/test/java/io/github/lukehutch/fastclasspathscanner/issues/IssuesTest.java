package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Named;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.issues.issue38.ImplementsNamed;

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
}
