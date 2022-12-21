package io.github.classgraph.issues.issue696;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.issues.issue696.Issue696.BrokenAnnotation.Dynamic;

public class Issue696 {
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
            public Dynamic(@Foo final String param1, @Bar final String param2) {
            }
        }
    }

    @Test
    void genericSuperclass() {
        final ScanResult scanResult = new ClassGraph().acceptPackages(Issue696.class.getPackage().getName())
                .enableMethodInfo().enableAnnotationInfo().scan();
        final ClassInfo dynamic = scanResult.getClassInfo(Dynamic.class.getName());
        final MethodParameterInfo[] paramInfo = dynamic.getConstructorInfo().get(0).getParameterInfo();
        // Inner classes have an initial "mandated" param
        assertThat(paramInfo.length).isEqualTo(3);
        assertThat(paramInfo[0].getAnnotationInfo()).isEmpty();
        assertThat(paramInfo[1].getAnnotationInfo().get(0).getName()).isEqualTo(Foo.class.getName());
        assertThat(paramInfo[2].getAnnotationInfo().get(0).getName()).isEqualTo(Bar.class.getName());
    }
}
