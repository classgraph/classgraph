package io.github.classgraph.issues.issue706;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeArgument;
import io.github.classgraph.TypeVariableSignature;

public class Issue706 {
    static public class GenericBase<T> {
    }

    static public class GenericBypass<T> extends GenericBase<T> {
    }

    @Test
    void genericSuperclass() {
        final ScanResult scanResult = new ClassGraph().acceptPackages(Issue706.class.getPackage().getName())
                .enableClassInfo().scan();
        final ClassInfo bypassCls = scanResult.getClassInfo(GenericBypass.class.getName());
        final TypeArgument superclassArg = bypassCls.getTypeSignature().getSuperclassSignature()
                .getSuffixTypeArguments().get(0).get(0);
        final TypeVariableSignature superclassArgTVar = (TypeVariableSignature) superclassArg.getTypeSignature();
        final Object bypassTParamFromSuperclassArg = superclassArgTVar.resolve();
        assertThat(bypassTParamFromSuperclassArg.toString()).isEqualTo("T");
    }
}
