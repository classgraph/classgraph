package io.github.lukehutch.fastclasspathscanner.issues.issue38;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue38Test {
    public static abstract class AnnotationLiteral<T extends Annotation> implements Annotation {
    }

    @Test
    public void testImplementsSuppressWarnings() {
        assertThat(new FastClasspathScanner().whitelistPackages(Issue38Test.class.getPackage().getName()).scan()
                .getClassesImplementing(SuppressWarnings.class.getName()).getNames())
                        .containsOnly(ImplementsSuppressWarnings.class.getName());
    }
}
