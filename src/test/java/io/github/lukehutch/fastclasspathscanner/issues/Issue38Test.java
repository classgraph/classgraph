package io.github.lukehutch.fastclasspathscanner.issues;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue38Test {

    @Test
    public void test() {
        new FastClasspathScanner("io/github/lukehutch/fastclasspathscanner/issues/thirtyeight")
            // .matchClassesWithAnnotation(WebService.class, new ClassAnnotationMatchProcessor() {
            // @Override
            // public void processMatch(Class<?> matchingClass) {
            // System.out.println("found: " + matchingClass.getName());
            // }
            // })
            .scan();
    }
}
