package io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The default parameter values of an annotation are kept when a scan result is serialized to JSON and back.
 */
@SuppressWarnings("unused")
public class AnnotationDefaultValsTest {
    @Retention(RetentionPolicy.RUNTIME)
    @interface MyAnnotation {
        String msg() default "hello";
    }

    @MyAnnotation
    class MyClass {
    }

    /**
     * Test serialize then deserialize scan result.
     */
    @Test
    public void testSerializeThenDeserializeWithAnnotation() {
        // Get URL base for overriding classpath (otherwise the JSON representation of the ScanResult won't be
        // the same after the first and second deserialization, because overrideClasspath is set by the first
        // serialization for consistency.)
        final String classfileURL = getClass().getClassLoader()
                .getResource(AnnotationDefaultValsTest.class.getName().replace('.', '/') + ".class").toString();
        final String classpathBase = classfileURL.substring(0,
                classfileURL.length() - (AnnotationDefaultValsTest.class.getName().length() + 6));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpathBase)
                .acceptPackagesNonRecursive(AnnotationDefaultValsTest.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {
            assertThat(scanResult.getClassInfo(MyClass.class.getName()).getAnnotationInfo().get(0)
                    .getDefaultParameterValues().get(0).getValue()).isEqualTo("hello");
            final int indent = 2;
            final String scanResultJSON = scanResult.toJSON(indent);
            try (ScanResult scanResultDeserialized = ScanResult.fromJSON(scanResultJSON)) {
                final String scanResultReserializedJSON = scanResultDeserialized.toJSON(indent);
                assertThat(scanResultReserializedJSON).isEqualTo(scanResultJSON);
                assertThat(scanResultDeserialized.getClassInfo(MyClass.class.getName()).getAnnotationInfo().get(0)
                        .getDefaultParameterValues().get(0).getValue()).isEqualTo("hello");
            }
        }
    }
}
