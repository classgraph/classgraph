package io.github.classgraph.issues.issue741;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeParameter;

public class TypeParameterAnnotationTest {
    @Target({ ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface A {
    }

    @Target({ ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface B {
        String value();
    }

    @Target({ ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface C {
        Class<?> t();
    }

    @Target({ ElementType.FIELD, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    private static @interface D {
        int n();
    }

    static class U {
    }

    <@A @B("foo") @C(t = U.class) @D(n = 50) T> void setValue(final T value) {
    }

    @Test
    void typeParameterAnnotation() {
        try (final ScanResult scanResult = new ClassGraph()
                .acceptPackages(TypeParameterAnnotationTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo cls = scanResult.getClassInfo(TypeParameterAnnotationTest.class.getName());
            final MethodInfo method = cls.getMethodInfo().get("setValue").get(0);
            final TypeParameter typeParameter = method.getTypeSignatureOrTypeDescriptor().getTypeParameters()
                    .get(0);
            final AnnotationInfoList annotationInfoList = typeParameter.getTypeAnnotationInfo();
            assertThat(annotationInfoList.get(0).toStringWithSimpleNames()).isEqualTo("@A");
            assertThat(annotationInfoList.get(1).toStringWithSimpleNames()).isEqualTo("@B(\"foo\")");
            assertThat(annotationInfoList.get(2).toStringWithSimpleNames()).isEqualTo("@C(t=U.class)");
            assertThat(annotationInfoList.get(3).toStringWithSimpleNames()).isEqualTo("@D(n=50)");
        }
    }
}
