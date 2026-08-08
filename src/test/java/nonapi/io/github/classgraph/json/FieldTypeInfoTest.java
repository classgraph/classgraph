package nonapi.io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;

/**
 * Tests for {@link FieldTypeInfo}.
 *
 * <p>
 * {@code Class<?>} is a reference type; it is listed in
 * {@code FieldTypeInfo.PrimitiveType} only so that {@code setFieldValue} can
 * type-check it. The null check in {@code setFieldValue} used to treat every
 * type other than {@code NON_PRIMITIVE} as a primitive type, so a
 * {@code Class<?>}-typed field with an explicit JSON null value could not be
 * deserialized.
 */
public class FieldTypeInfoTest {
    /**
     * A class with a {@code Class<?>}-typed field, used as the deserialization
     * target.
     */
    public static class ClassRefHolder {
        /** A reference-typed field that may legitimately be null. */
        public Class<?> cls;

        /** An ordinary reference-typed field, for comparison. */
        public String name;
    }

    /**
     * A null value for a {@code Class<?>}-typed field must deserialize to null, not
     * throw.
     */
    @Test
    public void nullClassRefFieldCanBeDeserialized() {
        final var holder = JSONDeserializer.deserializeObject(ClassRefHolder.class,
                "{\"cls\": null, \"name\": null}");
        assertThat(holder.cls).isNull();
        assertThat(holder.name).isNull();
    }

    /** A non-null value for a {@code Class<?>}-typed field still round-trips. */
    @Test
    public void nonNullClassRefFieldStillDeserializes() {
        final var holder = JSONDeserializer.deserializeObject(ClassRefHolder.class,
                "{\"cls\": \"java.lang.String\", \"name\": \"x\"}");
        assertThat(holder.cls).isEqualTo(String.class);
        assertThat(holder.name).isEqualTo("x");
    }

    /**
     * {@link FieldTypeInfo#toString()} must name the field, not repeat the
     * declaring class.
     */
    @Test
    public void toStringNamesTheField() throws NoSuchFieldException {
        final var classFieldCache = new ClassFieldCache(true, false, new ReflectionUtils());
        final var fieldTypeInfo = new FieldTypeInfo(ClassRefHolder.class.getField("name"), String.class,
                classFieldCache);
        assertThat(fieldTypeInfo.toString()).endsWith("." + "name");
    }
}
