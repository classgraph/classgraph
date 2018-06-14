package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class TestGetUniqueClasspathElements {
    @Test
    public void testGetUniqueClasspathElements() {
        assertThat(new FastClasspathScanner("!!").getUniqueClasspathElements()).isNotEmpty();
        assertThat(new FastClasspathScanner("!!").getUniqueClasspathElementURLs()).isNotEmpty();
        assertThat(new FastClasspathScanner("!!").getUniqueClasspathElementsAsPathStr()).isNotEmpty();
    }
}
