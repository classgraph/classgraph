package io.github.classgraph.issues.issue706;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.TypeVariableSignature;

public class Issue706Test {
    // The type parameter is the fixture, and is deliberately not used by the body
    @SuppressWarnings("unused")
    public static class GenericBase<T> {
    }

    public static class GenericBypass<T> extends GenericBase<T> {
    }

    @Test
    void genericSuperclass() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue706Test.class.getPackage().getName())
                .enableClassInfo().scan()) {
            final var bypassCls = scanResult.getClassInfo(GenericBypass.class.getName());
            final var superclassArg = bypassCls.getTypeSignature().getSuperclassSignature().getSuffixTypeArguments()
                    .get(0).get(0);
            final var superclassArgTVar = (TypeVariableSignature) superclassArg.getTypeSignature();
            final Object bypassTParamFromSuperclassArg = superclassArgTVar.resolve();
            assertThat(bypassTParamFromSuperclassArg.toString()).isEqualTo("T");
        }
    }
}
