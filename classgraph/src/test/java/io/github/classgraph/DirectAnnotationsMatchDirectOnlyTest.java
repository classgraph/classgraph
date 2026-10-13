package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link HasAnnotations#getDirectAnnotationInfo()} and {@link ClassInfo#getDirectAnnotations()} are computed
 * without finding meta-annotations or inherited annotations, and give the same lists, in the same order, as
 * {@code getAllAnnotationInfo().directOnly()} and {@code getAllAnnotations().directOnly()}. This is checked for
 * every class, field, method, method parameter and package of the ClassGraph main and test classes, which include
 * the repeated annotations of {@link RepeatableAnnotationTest}, annotations on external classes, and inherited
 * annotations.
 */
public class DirectAnnotationsMatchDirectOnlyTest {
    /**
     * Check every annotated element of a scan.
     *
     * @param enableExternalClasses
     *            whether external classes are returned by queries, which changes which annotation classes
     *            {@link ClassInfo#getDirectAnnotations()} returns.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void directAnnotationsMatchDirectOnly(final boolean enableExternalClasses) {
        final var classGraph = new ClassGraph().enableClasspath().acceptPackages("io.github.classgraph")
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility();
        if (enableExternalClasses) {
            classGraph.enableExternalClasses();
        }
        try (var scanResult = classGraph.scan()) {
            var annotatedClasses = 0;
            var repeatedAnnotations = 0;
            for (final ClassInfo classInfo : scanResult.getAllClasses()) {
                assertThat(classInfo.getDirectAnnotations()).as(classInfo.getName())
                        .containsExactlyElementsOf(classInfo.getAllAnnotations().directOnly());
                assertDirectAnnotationInfoMatches(classInfo, classInfo.getName());
                if (!classInfo.getDirectAnnotationInfo().isEmpty()) {
                    annotatedClasses++;
                }
                if (classInfo.getDirectAnnotationInfo().getRepeatable(RepeatableAnnotationTest.Tag.class)
                        .size() > 1) {
                    repeatedAnnotations++;
                }
                for (final FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    assertDirectAnnotationInfoMatches(fieldInfo, fieldInfo.toString());
                }
                for (final MethodInfo methodInfo : classInfo.getDeclaredMethodAndConstructorInfo()) {
                    assertDirectAnnotationInfoMatches(methodInfo, methodInfo.toString());
                    if (methodInfo.getDirectAnnotationInfo().getRepeatable(RepeatableAnnotationTest.Tag.class)
                            .size() > 1) {
                        repeatedAnnotations++;
                    }
                    for (final MethodParameterInfo parameterInfo : methodInfo.getParameterInfo()) {
                        assertDirectAnnotationInfoMatches(parameterInfo, methodInfo + " " + parameterInfo);
                    }
                }
            }
            for (final PackageInfo packageInfo : scanResult.getPackageInfo()) {
                assertDirectAnnotationInfoMatches(packageInfo, packageInfo.getName());
            }
            // The scan reached the cases that the lists could disagree on
            assertThat(annotatedClasses).isGreaterThan(100);
            assertThat(repeatedAnnotations).isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * Check that the direct annotations of an element are the direct annotations of its list of all annotations.
     *
     * @param annotated
     *            the annotated element.
     * @param description
     *            the description of the element, for the failure message.
     */
    private static void assertDirectAnnotationInfoMatches(final HasAnnotations annotated,
            final String description) {
        final var expected = annotated.getAllAnnotationInfo().directOnly();
        final var actual = annotated.getDirectAnnotationInfo();
        assertThat(actual).as(description).containsExactlyElementsOf(expected);
        assertThat(actual.directOnly()).as(description).isSameAs(actual);
    }
}
