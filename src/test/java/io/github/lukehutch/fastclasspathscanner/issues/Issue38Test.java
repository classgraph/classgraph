package io.github.lukehutch.fastclasspathscanner.issues;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import org.junit.Test;

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
