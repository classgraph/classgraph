package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.TypeVariableSignature;

/**
 * ResolveTypeVariable.
 *
 * @param <T>
 *            the generic type
 */
public class ResolveTypeVariableTest<T extends ArrayList<Integer>> {
    /** The list. */
    @SuppressWarnings("null")
    T list;

    /**
     * Test.
     */
    @Test
    public void test() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ResolveTypeVariableTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var fields = scanResult.getClassInfo(ResolveTypeVariableTest.class.getName()).getFieldInfo();
            assertThat(((TypeVariableSignature) fields.get(0).getTypeSignature()).resolve().toString())
                    .isEqualTo("T extends java.util.ArrayList<java.lang.Integer>");
        }
    }
}
