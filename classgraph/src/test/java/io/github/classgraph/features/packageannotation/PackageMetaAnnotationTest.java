package io.github.classgraph.features.packageannotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that {@link io.github.classgraph.PackageInfo#getAllAnnotationInfo()} resolves meta-annotations on package
 * annotations, in the same way as the equivalent method on classes and class members, and that
 * {@link io.github.classgraph.PackageInfo#getDirectAnnotationInfo()} does not.
 */
public class PackageMetaAnnotationTest {
    /** Meta-annotations of a package annotation should be reachable, but not directly present. */
    @Test
    public void packageMetaAnnotationsAreResolved() {
        final var packageName = PackageMetaAnnotationTest.class.getPackage().getName();
        try (var scanResult = new ClassGraph().enableClasspath().acceptPackages(packageName).enableAnnotationInfo()
                // package-info is a non-public class
                .ignoreClassVisibility().scan()) {
            final var packageInfo = Objects.requireNonNull(scanResult.getPackageInfo(packageName));

            assertThat(packageInfo.getAllAnnotationInfo().getNames()).containsExactlyInAnyOrder(
                    MetaAnnotatedPackageAnnotation.class.getName(), MetaAnnotation.class.getName());
            assertThat(packageInfo.getDirectAnnotationInfo().getNames())
                    .containsExactly(MetaAnnotatedPackageAnnotation.class.getName());

            assertThat(packageInfo.hasAnnotation(MetaAnnotation.class)).isTrue();
            assertThat(packageInfo.getAllAnnotationInfo(MetaAnnotation.class)).isNotNull();
            assertThat(packageInfo.getDirectAnnotationInfo(MetaAnnotation.class)).isNull();
            assertThat(packageInfo.getDirectAnnotationInfo(MetaAnnotatedPackageAnnotation.class)).isNotNull();

            assertThat(packageInfo.getAllAnnotationInfoRepeatable(MetaAnnotation.class)).hasSize(1);
            assertThat(packageInfo.getDirectAnnotationInfoRepeatable(MetaAnnotation.class)).isEmpty();
        }
    }

    /**
     * Reading package annotations without calling {@link ClassGraph#enableAnnotationInfo()} should fail loudly,
     * rather than reporting that the package has no annotations.
     */
    @Test
    public void annotationInfoMustBeEnabled() {
        final var packageName = PackageMetaAnnotationTest.class.getPackage().getName();
        try (var scanResult = new ClassGraph().enableClasspath().acceptPackages(packageName).ignoreClassVisibility()
                .scan()) {
            final var packageInfo = Objects.requireNonNull(scanResult.getPackageInfo(packageName));

            assertThatThrownBy(packageInfo::getAllAnnotationInfo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableAnnotationInfo");
            assertThatThrownBy(() -> packageInfo.hasAnnotation(MetaAnnotation.class))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
