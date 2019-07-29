package io.github.classgraph.issues.issue355;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.ArrayClassInfo;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue355 {
    /** Annotation parameter class */
    public class X {
    }

    /** Annotation with class reference array typed param */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ann {
        /** Annotation parameter */
        public Class<?>[] value();
    }

    /** Annotated with class reference array */
    @Ann({ X.class })
    public class Y {
        /** method with array-typed param */
        public void y(final X[] x) {
        }
    }

    /** Test **/
    @Test
    public void test() throws IOException {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackagesNonRecursive(Issue355.class.getPackage().getName()).enableClassInfo()
                .enableInterClassDependencies().scan()) {
            final ClassInfo y = scanResult.getClassInfo(Y.class.getName());
            final ClassInfo x = scanResult.getClassInfo(X.class.getName());
            assertThat(y).isNotNull();
            assertThat(x).isNotNull();

            // Test array-typed annotation parameter
            final Object annParamVal = ((Object[]) y.getAnnotationInfo().get(0).getParameterValues().get(0)
                    .getValue())[0];
            assertThat(annParamVal).isInstanceOf(AnnotationClassRef.class);
            final AnnotationClassRef annClassRef = (AnnotationClassRef) annParamVal;
            assertThat(annClassRef.getClassInfo().getName()).isEqualTo(X.class.getName());

            // Test class dep from annotation param of array element type shows up in class deps
            final ClassInfoList yDeps = scanResult.getClassDependencyMap().get(y);
            assertThat(yDeps).isNotNull();
            assertThat(yDeps).contains(x);

            // Test array-typed method parameter
            final MethodParameterInfo yParam = y.getMethodInfo().get(0).getParameterInfo()[0];
            final ArrayTypeSignature paramTypeSignature = (ArrayTypeSignature) yParam
                    .getTypeSignatureOrTypeDescriptor();
            final ArrayClassInfo arrayClassInfo = paramTypeSignature.getArrayClassInfo();
            assertThat(arrayClassInfo.getElementClassInfo().equals(x));
            assertThat(arrayClassInfo.loadClass()).isEqualTo(X[].class);
            assertThat(arrayClassInfo.loadElementClass()).isEqualTo(X.class);
        }
    }
}
