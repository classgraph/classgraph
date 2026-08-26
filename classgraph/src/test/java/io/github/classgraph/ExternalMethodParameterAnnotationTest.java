package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.test.paramannotation.external.ExternalParamAnnotation;
import io.github.classgraph.test.paramannotation.external.ParamMetaAnnotation;
import io.github.classgraph.test.paramannotation.internal.UsesExternalParamAnnotation;

/**
 * Scanning is extended upwards to the classfile of an annotation that is used outside the accepted packages, so
 * that the annotation's own meta-annotations are known. This was only done for the parameter annotations of a
 * method that also had an annotation of its own, since the loop over the parameter annotations was nested inside
 * the check for the method's own annotations.
 */
public class ExternalMethodParameterAnnotationTest {
    /** The classfile of an external method parameter annotation is read, even if the method has no annotation. */
    @Test
    public void scanningIsExtendedToExternalMethodParameterAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(UsesExternalParamAnnotation.class.getPackage().getName()).enableAllInfo().scan()) {
            final var annotation = scanResult.getClassInfo(ExternalParamAnnotation.class.getName());
            assertThat(annotation).isNotNull();
            assertThat(annotation.isAnnotation()).isTrue();
            // The meta-annotation is only in the class graph if the annotation's own classfile was read
            assertThat(scanResult.getClassInfo(ParamMetaAnnotation.class.getName())).isNotNull();
        }
    }
}
