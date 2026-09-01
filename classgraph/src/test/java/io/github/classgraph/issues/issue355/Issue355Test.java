package io.github.classgraph.issues.issue355;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;

/**
 * Unit test.
 */
public class Issue355Test {

    /**
     * Annotation parameter class.
     */
    public class X {
    }

    /**
     * Annotation with class reference array typed param.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ann {

        /**
         * Annotation parameter.
         *
         * @return the class[]
         */
        public Class<?>[] value();
    }

    /**
     * Annotated with class reference array.
     */
    @Ann({ X.class })
    public class Y {

        /**
         * method with array-typed param.
         *
         * @param x
         *            the x
         */
        public void y(final X[] x) {
        }
    }

    /**
     * Test.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Test
    public void test() throws IOException {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(Issue355Test.class.getPackage().getName()).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().enableInterClassDependencies().scan()) {
            final var y = scanResult.getClassInfo(Y.class.getName());
            final var x = scanResult.getClassInfo(X.class.getName());
            assertThat(y).isNotNull();
            assertThat(x).isNotNull();

            // Test array-typed annotation parameter
            final var annParamVal = ((Object[]) y.getAllAnnotationInfo().get(0).getParameterValues().get(0)
                    .getValue())[0];
            assertThat(annParamVal).isInstanceOf(AnnotationClassRef.class);
            final var annClassRef = (AnnotationClassRef) annParamVal;
            assertThat(annClassRef.getClassInfo().getName()).isEqualTo(X.class.getName());

            // Test class dep from annotation param of array element type shows up in class deps
            final var yDeps = scanResult.getClassDependencyMap().get(y);
            assertThat(yDeps).isNotNull();
            assertThat(yDeps).contains(x);

            // Test array-typed method parameter
            final var yParam = y.getMethodInfo().get(0).getParameterInfo().get(0);
            final var paramTypeSignature = (ArrayTypeSignature) yParam.getTypeSignatureOrTypeDescriptor();
            final var arrayClassInfo = paramTypeSignature.getArrayClassInfo();
            assertThat(arrayClassInfo.getElementClassInfo()).isEqualTo(x);
            // The element type of the array is named using the binary name form
            assertThat(arrayClassInfo.getName()).isEqualTo(X.class.getName() + "[]");
            assertThat(arrayClassInfo.getNumDimensions()).isEqualTo(1);
        }
    }
}
