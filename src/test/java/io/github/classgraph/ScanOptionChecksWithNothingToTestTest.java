package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A method that needs a scan option throws when the option was not enabled, even when there is no class, method,
 * field or parameter to ask, rather than returning an empty result.
 */
public class ScanOptionChecksWithNothingToTestTest {
    /** A class with no fields, and no methods other than its constructor. */
    public static class NoMembers {
    }

    /** The name of the annotation asked for. Whether it exists does not matter. */
    private static final String ANNOTATION = Deprecated.class.getName();

    /** The member annotation methods need annotation info, even when there is no member to test. */
    @Test
    public void memberAnnotationMethodsNeedAnnotationInfo() {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(NoMembers.class.getName()).enableClassInfo()
                .enableMethodInfo().enableFieldInfo().scan()) {
            final ClassInfo noMembers = scanResult.getClassInfo(NoMembers.class.getName());
            assertThat(noMembers.getMethodInfo()).isEmpty();
            assertThat(noMembers.getFieldInfo()).isEmpty();
            assertThatThrownBy(() -> noMembers.getMethodInfoWithAnnotation(ANNOTATION))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableAnnotationInfo");
            assertThatThrownBy(() -> noMembers.getDeclaredMethodInfoWithAnnotation(ANNOTATION))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableAnnotationInfo");
            assertThatThrownBy(() -> noMembers.getFieldInfoWithAnnotation(ANNOTATION))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableAnnotationInfo");
            assertThatThrownBy(() -> noMembers.getDeclaredFieldInfoWithAnnotation(ANNOTATION))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableAnnotationInfo");

            // A method with no parameters needs annotation info too, to answer whether a parameter is annotated
            final MethodInfo constructor = noMembers.getConstructorInfo().get(0);
            assertThat(constructor.getParameterInfo()).isEmpty();
            assertThatThrownBy(() -> constructor.hasParameterAnnotation(ANNOTATION))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enableAnnotationInfo");
        }
    }

    /** Both class dependency maps need inter-class dependencies, even when no class was found. */
    @Test
    public void classDependencyMapsNeedInterClassDependencies() {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(NoMembers.class.getName() + "DoesNotExist")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses()).isEmpty();
            assertThatThrownBy(scanResult::getClassDependencyMap).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("enableInterClassDependencies");
            assertThatThrownBy(scanResult::getReverseClassDependencyMap)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("enableInterClassDependencies");
        }
    }
}
