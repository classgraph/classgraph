package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

public class DeclaredVsNonDeclared {

    public abstract static class A {
        float x;

        abstract void y(int x, int y);

        abstract void y(String x);

        abstract void y(Integer x);
    }

    public abstract static class B extends A {
        int x;

        @Override
        void y(final int x, final int y) {
        }
    }

    @Test
    public void declaredVsNonDeclared() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
                .whitelistPackages(DeclaredVsNonDeclared.class.getPackage().getName()).scan()) {
            final ClassInfo A = scanResult.getClassInfo(A.class.getName());
            final ClassInfo B = scanResult.getClassInfo(B.class.getName());

            assertThat(A.getFieldInfo().get(0).getTypeDescriptor().toString()).isEqualTo("float");
            assertThat(B.getFieldInfo().get(0).getTypeDescriptor().toString()).isEqualTo("int");
            assertThat(B.getMethodInfo().toString()).isEqualTo(
                    "[void y(int, int), abstract void y(java.lang.String), abstract void y(java.lang.Integer)]");
            assertThat(B.getDeclaredMethodInfo().toString()).isEqualTo("[void y(int, int)]");
        }
    }
}
