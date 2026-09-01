package io.github.classgraph.issues.issue696;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.issues.issue696.Issue696Test.BrokenAnnotation.Dynamic;

public class Issue696Test {
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    public static @interface Foo {
    }

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    public static @interface Bar {
    }

    public static class BrokenAnnotation {
        public class Dynamic {
            // The annotations on these parameters are the fixture -- the parameters themselves are never used
            @SuppressWarnings("unused")
            public Dynamic(@Foo final String param1, @Bar final String param2) {
            }
        }
    }

    @Test
    void genericSuperclass() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptPackages(Issue696Test.class.getPackage().getName()).enableMethodInfo().enableAnnotationInfo()
                .scan()) {
            final var dynamic = scanResult.getClassInfo(Dynamic.class.getName());
            final var paramInfo = dynamic.getConstructorInfo().get(0).getParameterInfo();
            // Inner classes have an initial "mandated" param
            assertThat(paramInfo).hasSize(3);
            assertThat(paramInfo.get(0).getAllAnnotationInfo()).isEmpty();
            assertThat(paramInfo.get(1).getAllAnnotationInfo().get(0).getName()).isEqualTo(Foo.class.getName());
            assertThat(paramInfo.get(2).getAllAnnotationInfo().get(0).getName()).isEqualTo(Bar.class.getName());
        }
    }
}
