package io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 */
@SuppressWarnings("unused")
public class AnnotationDefaultVals {
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
                .getResource(AnnotationDefaultVals.class.getName().replace('.', '/') + ".class").toString();
        final String classpathBase = classfileURL.substring(0,
                classfileURL.length() - (AnnotationDefaultVals.class.getName().length() + 6));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpathBase)
                .acceptPackagesNonRecursive(AnnotationDefaultVals.class.getPackage().getName())
                .ignoreClassVisibility().enableAllInfo().scan()) {
            assertThat(scanResult.getClassInfo(MyClass.class.getName()).getAnnotationInfo().get(0)
                    .getDefaultParameterValues().get(0).getValue()).isEqualTo("hello");
            final int indent = 2;
            final String scanResultJSON = scanResult.toJSON(indent);
            final ScanResult scanResultDeserialized = ScanResult.fromJSON(scanResultJSON);
            final String scanResultReserializedJSON = scanResultDeserialized.toJSON(indent);
            assertThat(scanResultReserializedJSON).isEqualTo(scanResultJSON);
            assertThat(scanResultDeserialized.getClassInfo(MyClass.class.getName()).getAnnotationInfo().get(0)
                    .getDefaultParameterValues().get(0).getValue()).isEqualTo("hello");
        }
    }
}
