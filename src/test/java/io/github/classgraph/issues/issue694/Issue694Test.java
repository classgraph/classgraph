package io.github.classgraph.issues.issue694;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;

public class Issue694Test {
    static class TestClass {
    }

    public static <C extends Collection<TestClass>> C test(final C collection) {
        return collection;
    }

    @Test
    void methodWithParam() {
        final var scan = new ClassGraph().acceptClasses(Issue694Test.class.getName()).enableAnnotationInfo()
                .enableMethodInfo().scan();

        final List<String> foundMethods = new ArrayList<>();
        final List<String> foundMethodInfo = new ArrayList<>();
        for (final ClassInfo info : scan.getAllStandardClasses()) {
            for (final MethodInfo methodInfo : info.getDeclaredMethodInfo()) {
                foundMethodInfo.add(methodInfo.toString());
                final var method = methodInfo.loadClassAndGetMethod();
                foundMethods.add(method.toString());
            }
        }
        assertThat(foundMethodInfo).containsOnly(
                "public static <C extends java.util.Collection<io.github.classgraph.issues.issue694.Issue694Test$TestClass>> C test(final C collection)");
        assertThat(foundMethods).containsOnly(
                "public static java.util.Collection io.github.classgraph.issues.issue694.Issue694Test.test(java.util.Collection)");
    }
}
