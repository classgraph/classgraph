package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeVariableSignature;

public class ResolveTypeVariable<T extends ArrayList<Integer>> {
    T list;

    @Test
    public void test() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(ResolveTypeVariable.class.getPackage().getName()).enableAllInfo().scan()) {
            final FieldInfoList fields = scanResult.getClassInfo(ResolveTypeVariable.class.getName())
                    .getFieldInfo();
            assertThat(((TypeVariableSignature) fields.get(0).getTypeSignature()).resolve().toString())
                    .isEqualTo("T extends java.util.ArrayList<java.lang.Integer>");
        }
    }
}
