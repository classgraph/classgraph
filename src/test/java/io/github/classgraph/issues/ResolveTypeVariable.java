package io.github.classgraph.issues;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.util.ArrayList;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.TypeSignature;
import io.github.classgraph.TypeVariableSignature;

public class ResolveTypeVariable<T extends ArrayList<Integer>> {

    T list;

    @Test
    public void test() {
        final TypeSignature typeSig = new ClassGraph()
                .whitelistPackages(ResolveTypeVariable.class.getPackage().getName()).enableAllInfo().scan()
                .getClassInfo(ResolveTypeVariable.class.getName()).getFieldInfo().get(0).getTypeSignature();
        assertThat(((TypeVariableSignature) typeSig).resolve().toString())
                .isEqualTo("T extends java.util.ArrayList<java.lang.Integer>");
    }
}
