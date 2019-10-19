package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.ScanResult;
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
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(ResolveTypeVariableTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final FieldInfoList fields = scanResult.getClassInfo(ResolveTypeVariableTest.class.getName())
                    .getFieldInfo();
            assertThat(((TypeVariableSignature) fields.get(0).getTypeSignature()).resolve().toString())
                    .isEqualTo("T extends java.util.ArrayList<java.lang.Integer>");
        }
    }
}
