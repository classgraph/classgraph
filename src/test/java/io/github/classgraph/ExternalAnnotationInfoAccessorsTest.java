package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.test.typeannotation.internal.UsesExternalTypeAnnotation;

/**
 * Tests the accessors of an {@link AnnotationInfo} whose annotation class was never scanned, so that no
 * {@link ClassInfo} object exists for it. A declaration annotation always gets a placeholder {@code ClassInfo},
 * because the annotation is recorded in the class graph, but a type annotation does not, so a type annotation
 * declared outside the accepted packages is the case where {@code AnnotationInfo#getClassInfo()} returns null.
 */
public class ExternalAnnotationInfoAccessorsTest {
    /** Open a scan that reads the annotated class but not the annotation class. */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(UsesExternalTypeAnnotation.class.getPackage().getName())
                .enableAllInfo().scan();
    }

    /** Return the {@link AnnotationInfo} of the type annotation on the field's type. */
    private static AnnotationInfo externalTypeAnnotationInfo(final ScanResult scanResult) {
        final ClassInfo classInfo = scanResult.getClassInfo(UsesExternalTypeAnnotation.class.getName());
        assertThat(classInfo).isNotNull();
        final FieldInfo fieldInfo = classInfo.getDeclaredFieldInfo("field");
        assertThat(fieldInfo).isNotNull();
        final AnnotationInfoList typeAnnotationInfo = fieldInfo.getTypeDescriptor().getTypeAnnotationInfo();
        assertThat(typeAnnotationInfo).isNotNull();
        assertThat(typeAnnotationInfo).hasSize(1);
        return typeAnnotationInfo.get(0);
    }

    /**
     * The annotation class of the external type annotation should genuinely be absent from the scan, otherwise the
     * two tests below would not be testing what they claim to test.
     */
    @Test
    public void externalTypeAnnotationClassIsNotScanned() {
        try (ScanResult scanResult = scan()) {
            final AnnotationInfo annotationInfo = externalTypeAnnotationInfo(scanResult);
            assertThat(scanResult.getClassInfo(annotationInfo.getName())).isNull();
        }
    }

    /**
     * An annotation whose class was not scanned is not known to be meta-annotated with
     * {@link java.lang.annotation.Inherited}, so {@link AnnotationInfo#isInherited()} should report false rather
     * than throwing {@link NullPointerException}.
     */
    @Test
    public void isInheritedReturnsFalseForUnscannedAnnotationClass() {
        try (ScanResult scanResult = scan()) {
            assertThat(externalTypeAnnotationInfo(scanResult).isInherited()).isFalse();
        }
    }

    /**
     * The default parameter values of an annotation are declared by the annotation class, so if that class was not
     * scanned, {@link AnnotationInfo#getDefaultParameterValues()} should return the empty list, as documented,
     * rather than throwing {@link NullPointerException}.
     */
    @Test
    public void getDefaultParameterValuesIsEmptyForUnscannedAnnotationClass() {
        try (ScanResult scanResult = scan()) {
            assertThat(externalTypeAnnotationInfo(scanResult).getDefaultParameterValues()).isEmpty();
        }
    }
}
