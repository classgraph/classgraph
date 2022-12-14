package io.github.classgraph.issues.issue735;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

public class Issue735 {
    static interface Base<T> {
        T get();
    }

    static class Derived1 implements Base<String> {
        public String get() {
            return null;
        }
    }

    static abstract class Derived2 implements Base<String> {
    }

    @Test
    void genericSuperclass() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages(Issue735.class.getPackage().getName())
                .enableAllInfo().ignoreClassVisibility().ignoreMethodVisibility().scan()) {
            ClassInfo ci1 = scanResult.getClassInfo(Derived1.class.getName());
            assertThat(ci1.getMethodInfo().get(0).getTypeSignatureOrTypeDescriptor().getResultType().toString())
                    .isEqualTo(String.class.getName());
            ClassInfo ci2 = scanResult.getClassInfo(Derived2.class.getName());
            assertThat(ci2.getMethodInfo().get(0).getTypeSignatureOrTypeDescriptor().getResultType().toString())
                    .isEqualTo("T");
        }
    }
}
