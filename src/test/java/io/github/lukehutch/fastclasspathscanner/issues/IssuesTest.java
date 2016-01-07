package io.github.lukehutch.fastclasspathscanner.issues;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import org.junit.Test;

public class IssuesTest {
    @Test
    public void testIssuesScan() {
        new FastClasspathScanner("io.github.lukehutch.fastclasspathscanner.issues").scan();
    }
}
