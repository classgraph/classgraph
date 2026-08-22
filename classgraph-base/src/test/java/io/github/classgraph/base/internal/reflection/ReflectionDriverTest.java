package io.github.classgraph.base.internal.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that both reflection drivers agree on whether a member is static, and that they agree with each other.
 *
 * <p>
 * Both drivers are exercised directly, rather than through {@link ReflectionUtils}: only one driver is chosen per
 * JVM, so a test that went through {@link ReflectionUtils} would only ever cover that one.
 */
public class ReflectionDriverTest {
    /** A class with a static and a non-static version of every kind of member the drivers can reach. */
    @SuppressWarnings({ "unused", "static-method" })
    private static class Fixture {
        /** A static field that is only read. */
        private static final String STATIC_FIELD = "static field";

        /** A static field that the set tests write to. */
        private static String mutableStaticField = "";

        /** An instance field that is only read. */
        private final String instanceField = "instance field";

        /** An instance field that the set tests write to. */
        private String mutableInstanceField = "";

        /**
         * A static method.
         *
         * @return a marker string.
         */
        private static String staticMethod() {
            return "static method";
        }

        /**
         * An instance method.
         *
         * @return a marker string.
         */
        private String instanceMethod() {
            return "instance method";
        }
    }

    /** An interface with a constant and a default method, to be reached through an implementing class. */
    @SuppressWarnings("unused")
    private interface Constants {
        /** A constant, which is implicitly static and final. */
        String INTERFACE_CONSTANT = "interface constant";

        /**
         * A default method.
         *
         * @return a marker string.
         */
        default String defaultMethod() {
            return "default method";
        }
    }

    /** A class that reaches {@link Constants} through the interface it implements. */
    private static class Implementor implements Constants {
    }

    /** An operation on a driver, which may fail. */
    @FunctionalInterface
    private interface DriverOp {
        /**
         * Run the operation.
         *
         * @return the result of the operation.
         * @throws Exception
         *             if the operation failed.
         */
        Object run() throws Exception;
    }

    /**
     * The drivers under test. Narcissus is a compile-scoped dependency of this module, so both drivers can always
     * be constructed here, whichever one {@link ReflectionUtils} ends up choosing at runtime.
     *
     * @return the drivers under test.
     * @throws Exception
     *             if a driver could not be constructed.
     */
    private static Stream<ReflectionDriver> drivers() throws Exception {
        return Stream.of(new StandardReflectionDriver(), new NarcissusReflectionDriver());
    }

    /**
     * Get a field of {@link Fixture} by name, whether static or not.
     *
     * @param fieldName
     *            the field name.
     * @return the field.
     * @throws Exception
     *             if the field could not be found.
     */
    private static Field field(final String fieldName) throws Exception {
        final Field field = Fixture.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    /**
     * Get a no-arg method of {@link Fixture} by name, whether static or not.
     *
     * @param methodName
     *            the method name.
     * @return the method.
     * @throws Exception
     *             if the method could not be found.
     */
    private static Method method(final String methodName) throws Exception {
        final Method method = Fixture.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method;
    }

    /**
     * Read a static field, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void staticFieldNeedsGetStaticField(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        assertThat(driver.getStaticField(field("STATIC_FIELD"))).isEqualTo("static field");
        assertThatThrownBy(() -> driver.getField(obj, field("STATIC_FIELD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is static -- call getStaticField() instead");
    }

    /**
     * Read a non-static field, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void instanceFieldNeedsGetField(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        assertThat(driver.getField(obj, field("instanceField"))).isEqualTo("instance field");
        assertThatThrownBy(() -> driver.getStaticField(field("instanceField")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not static -- call getField() instead");
    }

    /**
     * Write a static field, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void staticFieldNeedsSetStaticField(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        driver.setStaticField(field("mutableStaticField"), "written");
        assertThat(driver.getStaticField(field("mutableStaticField"))).isEqualTo("written");
        assertThatThrownBy(() -> driver.setField(obj, field("mutableStaticField"), "no"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is static -- call setStaticField() instead");
    }

    /**
     * Write a non-static field, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void instanceFieldNeedsSetField(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        driver.setField(obj, field("mutableInstanceField"), "written");
        assertThat(driver.getField(obj, field("mutableInstanceField"))).isEqualTo("written");
        assertThatThrownBy(() -> driver.setStaticField(field("mutableInstanceField"), "no"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not static -- call setField() instead");
    }

    /**
     * Invoke a static method, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void staticMethodNeedsInvokeStaticMethod(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        assertThat(driver.invokeStaticMethod(method("staticMethod"))).isEqualTo("static method");
        assertThatThrownBy(() -> driver.invokeMethod(obj, method("staticMethod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is static -- call invokeStaticMethod() instead");
    }

    /**
     * Invoke a non-static method, whether or not the driver is willing to.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a call that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void instanceMethodNeedsInvokeMethod(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        assertThat(driver.invokeMethod(obj, method("instanceMethod"))).isEqualTo("instance method");
        assertThatThrownBy(() -> driver.invokeStaticMethod(method("instanceMethod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not static -- call invokeMethod() instead");
    }

    /**
     * Look a member up by name: the static lookups only find static members, and the general lookups find a member
     * of either kind.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a lookup that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void staticLookupsOnlyFindStaticMembers(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();

        assertThat(driver.findStaticField(Fixture.class, "STATIC_FIELD").getName()).isEqualTo("STATIC_FIELD");
        assertThatThrownBy(() -> driver.findStaticField(Fixture.class, "instanceField"))
                .isInstanceOf(NoSuchFieldException.class).hasMessageContaining("instanceField is not static");

        assertThat(driver.findStaticMethod(Fixture.class, "staticMethod").getName()).isEqualTo("staticMethod");
        assertThatThrownBy(() -> driver.findStaticMethod(Fixture.class, "instanceMethod"))
                .isInstanceOf(NoSuchMethodException.class).hasMessageContaining("instanceMethod is not static");

        // The general lookups are indifferent to the modifier, since a caller probing an unknown class by name
        // cannot know which members that class happens to have made static
        assertThat(driver.findField(Fixture.class, obj, "STATIC_FIELD").getName()).isEqualTo("STATIC_FIELD");
        assertThat(driver.findField(Fixture.class, obj, "instanceField").getName()).isEqualTo("instanceField");
        assertThat(driver.findMethod(Fixture.class, obj, "staticMethod").getName()).isEqualTo("staticMethod");
        assertThat(driver.findMethod(Fixture.class, obj, "instanceMethod").getName()).isEqualTo("instanceMethod");
    }

    /**
     * Constants and default methods of an implemented interface are found through the implementing class.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a lookup that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void interfaceMembersAreFoundThroughImplementingClass(final ReflectionDriver driver) throws Exception {
        assertThat(driver.findStaticField(Implementor.class, "INTERFACE_CONSTANT").getName())
                .isEqualTo("INTERFACE_CONSTANT");
        assertThat(driver.findMethod(Implementor.class, new Implementor(), "defaultMethod").getName())
                .isEqualTo("defaultMethod");
    }

    /**
     * Members of an interface are found when the interface itself is the class being reflected on.
     *
     * @param driver
     *            the driver under test.
     * @throws Exception
     *             if a lookup that was expected to succeed failed.
     */
    @ParameterizedTest
    @MethodSource("drivers")
    void interfaceMembersAreFoundThroughInterfaceItself(final ReflectionDriver driver) throws Exception {
        assertThat(driver.findStaticField(Constants.class, "INTERFACE_CONSTANT").getName())
                .isEqualTo("INTERFACE_CONSTANT");
        assertThat(driver.findMethod(Constants.class, null, "defaultMethod").getName()).isEqualTo("defaultMethod");
    }

    /**
     * If the fields of a class cannot be read, its methods are still cached, and vice versa.
     *
     * @throws Exception
     *             if a lookup that was expected to succeed failed.
     */
    @Test
    void unreadableMembersOfOneKindDoNotHideTheOther() throws Exception {
        final Fixture obj = new Fixture();

        final ReflectionDriver noFields = new StandardReflectionDriver() {
            @Override
            Field[] getDeclaredFields(final Class<?> cls) throws Exception {
                throw new SecurityException("Cannot read the fields of " + cls.getName());
            }
        };
        assertThat(noFields.findMethod(Fixture.class, obj, "instanceMethod").getName()).isEqualTo("instanceMethod");
        assertThatThrownBy(() -> noFields.findField(Fixture.class, obj, "instanceField"))
                .isInstanceOf(NoSuchFieldException.class);

        final ReflectionDriver noMethods = new StandardReflectionDriver() {
            @Override
            Method[] getDeclaredMethods(final Class<?> cls) throws Exception {
                throw new SecurityException("Cannot read the methods of " + cls.getName());
            }
        };
        assertThat(noMethods.findField(Fixture.class, obj, "instanceField").getName()).isEqualTo("instanceField");
        assertThatThrownBy(() -> noMethods.findMethod(Fixture.class, obj, "instanceMethod"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    /**
     * Describe the outcome of an operation: either the value it returned, or the type and message of the exception
     * it threw.
     *
     * @param op
     *            the operation.
     * @return a description of the outcome.
     */
    private static String outcomeOf(final DriverOp op) {
        try {
            return "returned " + op.run();
        } catch (final Throwable e) {
            return "threw " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    /**
     * Describe the outcome of every static/non-static combination, for one driver.
     *
     * @param driver
     *            the driver.
     * @return one description per combination, in a fixed order.
     * @throws Exception
     *             if a fixture member could not be looked up.
     */
    private static List<String> outcomesFor(final ReflectionDriver driver) throws Exception {
        final Fixture obj = new Fixture();
        final List<String> outcomes = new ArrayList<>();
        outcomes.add(outcomeOf(() -> driver.getField(obj, field("instanceField"))));
        outcomes.add(outcomeOf(() -> driver.getField(obj, field("STATIC_FIELD"))));
        outcomes.add(outcomeOf(() -> driver.getStaticField(field("STATIC_FIELD"))));
        outcomes.add(outcomeOf(() -> driver.getStaticField(field("instanceField"))));
        outcomes.add(outcomeOf(() -> driver.invokeMethod(obj, method("instanceMethod"))));
        outcomes.add(outcomeOf(() -> driver.invokeMethod(obj, method("staticMethod"))));
        outcomes.add(outcomeOf(() -> driver.invokeStaticMethod(method("staticMethod"))));
        outcomes.add(outcomeOf(() -> driver.invokeStaticMethod(method("instanceMethod"))));
        outcomes.add(outcomeOf(() -> driver.findStaticField(Fixture.class, "STATIC_FIELD").getName()));
        outcomes.add(outcomeOf(() -> driver.findStaticField(Fixture.class, "instanceField").getName()));
        outcomes.add(outcomeOf(() -> driver.findStaticMethod(Fixture.class, "staticMethod").getName()));
        outcomes.add(outcomeOf(() -> driver.findStaticMethod(Fixture.class, "instanceMethod").getName()));
        outcomes.add(outcomeOf(() -> driver.findField(Fixture.class, obj, "STATIC_FIELD").getName()));
        outcomes.add(outcomeOf(() -> driver.findField(Fixture.class, obj, "instanceField").getName()));
        outcomes.add(outcomeOf(() -> driver.findMethod(Fixture.class, obj, "staticMethod").getName()));
        outcomes.add(outcomeOf(() -> driver.findMethod(Fixture.class, obj, "instanceMethod").getName()));
        return outcomes;
    }

    /**
     * The two drivers produce not just the same successes and failures, but the same exception types and messages.
     *
     * @throws Exception
     *             if a driver could not be constructed.
     */
    @Test
    void bothDriversBehaveIdentically() throws Exception {
        assertThat(outcomesFor(new StandardReflectionDriver()))
                .isEqualTo(outcomesFor(new NarcissusReflectionDriver()));
    }
}
