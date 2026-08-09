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
        final List<String> foundMethodDescriptors = new ArrayList<>();
        final List<String> foundMethodInfo = new ArrayList<>();
        try (var scan = new ClassGraph().acceptClasses(Issue694Test.class.getName()).enableAnnotationInfo()
                .enableMethodInfo().scan()) {
            for (final ClassInfo info : scan.getAllStandardClasses()) {
                for (final MethodInfo methodInfo : info.getDeclaredMethodInfo()) {
                    foundMethodInfo.add(methodInfo.toString());
                    // The type descriptor gives the erased types, whereas the type signature gives the generic
                    // types
                    foundMethodDescriptors.add(methodInfo.getTypeDescriptor().toString());
                }
            }
        }
        assertThat(foundMethodInfo).containsOnly(
                "public static <C extends java.util.Collection<io.github.classgraph.issues.issue694.Issue694Test$TestClass>> C test(final C collection)");
        assertThat(foundMethodDescriptors).containsOnly("java.util.Collection (java.util.Collection)");
    }
}
