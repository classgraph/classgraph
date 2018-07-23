package io.github.lukehutch.fastclasspathscanner.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class TestGetUniqueClasspathElements {
    @Test
    public void testGetUniqueClasspathElements() {
        final List<File> classpathElements = new FastClasspathScanner().whitelistPackages("com.xyz")
                .getClasspathFiles();
        assertThat(classpathElements).isNotEmpty();
    }
}
