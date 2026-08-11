package io.github.classgraph.issues.issue854;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

class Issue854Test {
    @Test
    void getFullyQualifiedClassName() {
        final var mainClassLoader = Issue854Test.class.getClassLoader();
        try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo().ignoreClassVisibility()
                .ignoreFieldVisibility().ignoreMethodVisibility().overrideClassLoaders(mainClassLoader)
                .acceptPackages("com.google.common.collect").scan()) {

            final var anonymousClass = "com.google.common.collect.TreeRangeMap$SubRangeMap$1";
            final var classInfo = scanResult.getClassInfo(anonymousClass);
            final var signature = classInfo.getTypeSignatureOrTypeDescriptor().getSuperclassSignature();

            // Before the fix to 854, type parameter token parsing did not stop at '.', so this gave
            // "com.google.common.collect.TreeRangeMap$SubRangeMap.SubRangeMapAsMap", whereas the fully-qualified
            // class name in the classfile is "com.google.common.collect.TreeRangeMap$SubRangeMap$SubRangeMapAsMap".
            final var subRangeMapAsMapClassName = signature.getFullyQualifiedClassName();
            assertThat(subRangeMapAsMapClassName)
                    .isEqualTo("com.google.common.collect.TreeRangeMap$SubRangeMap$SubRangeMapAsMap");
            assertNotNull(scanResult.getClassInfo(subRangeMapAsMapClassName));
        }
    }
}