package nonapi.io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests that an enum constant with a constant-specific class body is serialized as its name, like any other enum
 * constant.
 *
 * <p>
 * Such a constant is an instance of an anonymous subclass of the enum type, and {@link Class#isEnum()} is false for
 * that subclass. The serializer used to test {@code value.getClass().isEnum()}, so these constants were not
 * recognized as enum values, and were serialized as an (empty) object rather than as a quoted name -- which then
 * failed to deserialize at all.
 */
public class EnumWithConstantBodyTest {
    /** An ordinary enum, whose constants have no class body. */
    public enum PlainEnum {
        /** First constant. */
        A,
        /** Second constant. */
        B
    }

    /** An enum whose constants each have a constant-specific class body. */
    public enum EnumWithConstantBody {
        /** First constant. */
        A {
            @Override
            public String describe() {
                return "a";
            }
        },
        /** Second constant. */
        B {
            @Override
            public String describe() {
                return "b";
            }
        };

        /**
         * Describe this constant.
         *
         * @return the description
         */
        public abstract String describe();
    }

    /** A class with a field of each enum type. */
    public static class EnumHolder {
        /** A constant of an ordinary enum. */
        public PlainEnum plain = PlainEnum.A;

        /** A constant that has its own class body. */
        public EnumWithConstantBody withBody = EnumWithConstantBody.B;
    }

    /** Both kinds of enum constant must serialize to their name. */
    @Test
    public void enumWithConstantBodyIsSerializedAsItsName() {
        final String json = JSONSerializer.serializeObject(new EnumHolder(), 0, false);
        assertThat(json).isEqualTo("{\"plain\":\"A\",\"withBody\":\"B\"}");
    }

    /** Both kinds of enum constant must survive a round trip. */
    @Test
    public void enumWithConstantBodyRoundTrips() {
        final EnumHolder holder = new EnumHolder();
        holder.plain = PlainEnum.B;
        holder.withBody = EnumWithConstantBody.A;
        final String json = JSONSerializer.serializeObject(holder, 0, false);
        final EnumHolder deserialized = JSONDeserializer.deserializeObject(EnumHolder.class, json);
        assertThat(deserialized.plain).isEqualTo(PlainEnum.B);
        assertThat(deserialized.withBody).isEqualTo(EnumWithConstantBody.A);
        assertThat(deserialized.withBody.describe()).isEqualTo("a");
    }
}
