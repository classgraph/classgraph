package nonapi.io.github.classgraph.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that {@link ReflectionUtils} routes each member to the driver operation that matches whether the member is
 * static, and that the entry points taking a class reach static members only.
 *
 * <p>
 * Each test runs against both drivers, by replacing the driver that the {@link ReflectionUtils} constructor chose
 * according to the {@code ClassGraph.CIRCUMVENT_ENCAPSULATION} setting. Without that, only the driver named by the
 * setting would ever be covered.
 */
public class ReflectionUtilsTest {
    /** A class with a static and a non-static version of each kind of member the tests reach by name. */
    @SuppressWarnings({ "unused", "static-method" })
    private static class Fixture {
        /** A private static field. */
        private static final String STATIC_FIELD = "static field";

        /** A private instance field. */
        private final String instanceField = "instance field";

        /**
         * An instance method with no arguments. It stays an instance method even though it reads no instance
         * state, because that is what it is for: the tests need a member of each kind, so that both the static and
         * the non-static path through {@link ReflectionUtils} is taken. Making it static would silently move it to
         * the other path.
         * 
         * @return a marker string.
         */
        private String noArgs() {
            return "no args";
        }

        /**
         * An instance method with one argument, kept an instance method for the reason given on {@link #noArgs()}.
         * 
         * @param arg
         *            the argument.
         * @return a marker string.
         */
        private String oneArg(final String arg) {
            return "one arg: " + arg;
        }

        /**
         * A static method with no arguments.
         * 
         * @return a marker string.
         */
        private static String staticNoArgs() {
            return "static no args";
        }

        /**
         * A static method with one argument.
         * 
         * @param arg
         *            the argument.
         * @return a marker string.
         */
        private static String staticOneArg(final String arg) {
            return "static one arg: " + arg;
        }
    }

    /**
     * A {@link ReflectionUtils} backed by each of the drivers in turn.
     *
     * @return one {@link ReflectionUtils} per driver.
     * @throws Exception
     *             if the Narcissus driver could not be loaded.
     */
    static Stream<ReflectionUtils> reflectionUtils() throws Exception {
        final ReflectionUtils standard = new ReflectionUtils();
        standard.reflectionDriver = new StandardReflectionDriver();
        final ReflectionUtils narcissus = new ReflectionUtils();
        narcissus.reflectionDriver = new NarcissusReflectionDriver();
        return Stream.of(standard, narcissus);
    }

    /**
     * A static member is reached through the entry points that take an object, since a caller probing an unknown
     * class by name cannot know which of its members that class happens to have made static.
     *
     * @param reflectionUtils
     *            the {@link ReflectionUtils} under test.
     * @throws NoSuchFieldException
     *             if the fixture field could not be found.
     */
    @ParameterizedTest
    @MethodSource("reflectionUtils")
    public void staticMembersAreReachedThroughTheObjectEntryPoints(final ReflectionUtils reflectionUtils)
            throws NoSuchFieldException {
        final Fixture obj = new Fixture();
        assertThat(reflectionUtils.getFieldVal(true, obj, "STATIC_FIELD")).isEqualTo("static field");
        assertThat(reflectionUtils.getFieldVal(true, obj, Fixture.class.getDeclaredField("STATIC_FIELD")))
                .isEqualTo("static field");
        assertThat(reflectionUtils.invokeMethod(true, obj, "staticNoArgs")).isEqualTo("static no args");
        assertThat(reflectionUtils.invokeMethod(true, obj, "staticOneArg", String.class, "x"))
                .isEqualTo("static one arg: x");
        assertThat(reflectionUtils.invokeMethod(true, obj, "staticOneArg", new Class<?>[] { String.class },
                new Object[] { "x" })).isEqualTo("static one arg: x");
    }

    /**
     * A non-static member is still reached through the entry points that take an object.
     *
     * @param reflectionUtils
     *            the {@link ReflectionUtils} under test.
     * @throws NoSuchFieldException
     *             if the fixture field could not be found.
     */
    @ParameterizedTest
    @MethodSource("reflectionUtils")
    public void nonStaticMembersAreStillReachedThroughTheObjectEntryPoints(final ReflectionUtils reflectionUtils)
            throws NoSuchFieldException {
        final Fixture obj = new Fixture();
        assertThat(reflectionUtils.getFieldVal(true, obj, "instanceField")).isEqualTo("instance field");
        assertThat(reflectionUtils.getFieldVal(true, obj, Fixture.class.getDeclaredField("instanceField")))
                .isEqualTo("instance field");
        assertThat(reflectionUtils.invokeMethod(true, obj, "noArgs")).isEqualTo("no args");
        assertThat(reflectionUtils.invokeMethod(true, obj, "oneArg", String.class, "x")).isEqualTo("one arg: x");
        assertThat(reflectionUtils.invokeMethod(true, obj, "oneArg", new Class<?>[] { String.class },
                new Object[] { "x" })).isEqualTo("one arg: x");
    }

    /**
     * The entry points that take a class rather than an object are strict: a non-static member is not a static
     * member of the class, so looking one up fails in the same way as a member that does not exist at all.
     *
     * @param reflectionUtils
     *            the {@link ReflectionUtils} under test.
     */
    @ParameterizedTest
    @MethodSource("reflectionUtils")
    public void theStaticEntryPointsDoNotReachANonStaticMember(final ReflectionUtils reflectionUtils) {
        assertThat(reflectionUtils.getStaticFieldVal(false, Fixture.class, "instanceField")).isNull();
        assertThatThrownBy(() -> reflectionUtils.getStaticFieldVal(true, Fixture.class, "instanceField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Can't read field " + Fixture.class.getName() + ".instanceField");

        assertThat(reflectionUtils.invokeStaticMethod(false, Fixture.class, "noArgs")).isNull();
        assertThatThrownBy(() -> reflectionUtils.invokeStaticMethod(true, Fixture.class, "noArgs"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Method \"noArgs\" could not be invoked");
        assertThat(reflectionUtils.invokeStaticMethod(false, Fixture.class, "oneArg", String.class, "x")).isNull();
    }

    /**
     * The entry points that take a class reach a static member.
     *
     * @param reflectionUtils
     *            the {@link ReflectionUtils} under test.
     */
    @ParameterizedTest
    @MethodSource("reflectionUtils")
    public void theStaticEntryPointsReachAStaticMember(final ReflectionUtils reflectionUtils) {
        assertThat(reflectionUtils.getStaticFieldVal(true, Fixture.class, "STATIC_FIELD"))
                .isEqualTo("static field");
        assertThat(reflectionUtils.invokeStaticMethod(true, Fixture.class, "staticNoArgs"))
                .isEqualTo("static no args");
        assertThat(reflectionUtils.invokeStaticMethod(true, Fixture.class, "staticOneArg", String.class, "x"))
                .isEqualTo("static one arg: x");
    }
}
