package io.github.classgraph.base.internal.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/** Tests for {@link ReflectionUtils}. */
public class ReflectionUtilsTest {
    /** The reflection utilities under test. */

    /** A superclass, so that the tests can check that inherited members are found. */
    private static class Base {
        /** A private field of the superclass. */
        private final String baseField = "base field";

        /**
         * A private method of the superclass.
         *
         * @return a marker string.
         */
        private String baseMethod() {
            return "base method";
        }
    }

    /** A class whose private members the tests read and invoke. */
    private static class Sub extends Base {
        /** A private static field. */
        private static final String STATIC_FIELD = "static field";

        /** A private instance field. */
        private final String instanceField = "instance field";

        /**
         * A method with no arguments.
         *
         * @return a marker string.
         */
        private String noArgs() {
            return "no args";
        }

        /**
         * A method with one argument.
         *
         * @param arg
         *            the argument.
         * @return a marker string.
         */
        private String oneArg(final String arg) {
            return "one arg: " + arg;
        }

        /**
         * A method with two arguments, the second of them primitive-typed.
         *
         * @param str
         *            the first argument.
         * @param num
         *            the second argument.
         * @return a marker string.
         */
        private String twoArgs(final String str, final int num) {
            return "two args: " + str + " " + num;
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

        /** A method that throws. */
        private void throwsAnException() {
            throw new UnsupportedOperationException("thrown by the method");
        }
    }

    /** A private field is read by name, whether it is declared by the object's class or by a superclass. */
    @Test
    public void instanceFieldValuesAreReadByName() {
        final var obj = new Sub();
        assertThat(ReflectionUtils.getFieldVal(true, obj, "instanceField")).isEqualTo("instance field");
        assertThat(ReflectionUtils.getFieldVal(true, obj, "baseField")).isEqualTo("base field");
    }

    /** A field that has already been looked up is read directly. */
    @Test
    public void instanceFieldValuesAreReadFromAFieldObject() throws NoSuchFieldException {
        final var obj = new Sub();
        final var field = Sub.class.getDeclaredField("instanceField");
        assertThat(ReflectionUtils.getFieldVal(true, obj, field)).isEqualTo("instance field");
        // Reading a field of one class from an instance of another fails
        assertThatThrownBy(() -> ReflectionUtils.getFieldVal(true, "not a Sub", field))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Can't read field java.lang.String.instanceField");
        assertThat(ReflectionUtils.getFieldVal(false, "not a Sub", field)).isNull();
    }

    /** A private static field is read by name. */
    @Test
    public void staticFieldValuesAreReadByName() {
        assertThat(ReflectionUtils.getStaticFieldVal(true, Sub.class, "STATIC_FIELD")).isEqualTo("static field");
    }

    /**
     * A field that does not exist yields null, or an exception naming the field, according to the throwException
     * argument.
     */
    @Test
    public void aFieldThatDoesNotExistYieldsNullOrThrows() {
        final var obj = new Sub();
        assertThat(ReflectionUtils.getFieldVal(false, obj, "noSuchField")).isNull();
        assertThatThrownBy(() -> ReflectionUtils.getFieldVal(true, obj, "noSuchField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Can't read field " + Sub.class.getName() + ".noSuchField");

        assertThat(ReflectionUtils.getStaticFieldVal(false, Sub.class, "noSuchField")).isNull();
        assertThatThrownBy(() -> ReflectionUtils.getStaticFieldVal(true, Sub.class, "noSuchField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Can't read field " + Sub.class.getName() + ".noSuchField");
    }

    /** Private methods are invoked by name, on the object's own class or on a superclass. */
    @Test
    public void instanceMethodsAreInvokedByName() {
        final var obj = new Sub();
        assertThat(ReflectionUtils.invokeMethod(true, obj, "noArgs")).isEqualTo("no args");
        assertThat(ReflectionUtils.invokeMethod(true, obj, "baseMethod")).isEqualTo("base method");
        assertThat(ReflectionUtils.invokeMethod(true, obj, "oneArg", String.class, "x")).isEqualTo("one arg: x");
        assertThat(ReflectionUtils.invokeMethod(true, obj, "twoArgs", new Class<?>[] { String.class, Integer.TYPE },
                new Object[] { "x", 5 })).isEqualTo("two args: x 5");
    }

    /** Private static methods are invoked by name. */
    @Test
    public void staticMethodsAreInvokedByName() {
        assertThat(ReflectionUtils.invokeStaticMethod(true, Sub.class, "staticNoArgs")).isEqualTo("static no args");
        assertThat(ReflectionUtils.invokeStaticMethod(true, Sub.class, "staticOneArg", String.class, "x"))
                .isEqualTo("static one arg: x");
    }

    /**
     * A method that does not exist, or that cannot be called with the given arguments, yields null or an exception
     * naming the method, according to the throwException argument.
     */
    @Test
    public void aMethodThatCannotBeInvokedYieldsNullOrThrows() {
        final var obj = new Sub();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "noSuchMethod")).isNull();
        assertThatThrownBy(() -> ReflectionUtils.invokeMethod(true, obj, "noSuchMethod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method \"noSuchMethod\" could not be invoked");
        // The method exists, but not with the given parameter types
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", Integer.TYPE, 5)).isNull();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", new Class<?>[] {}, new Object[] {})).isNull();

        assertThat(ReflectionUtils.invokeStaticMethod(false, Sub.class, "noSuchMethod")).isNull();
        assertThatThrownBy(() -> ReflectionUtils.invokeStaticMethod(true, Sub.class, "noSuchMethod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Method \"noSuchMethod\" could not be invoked");
        assertThat(ReflectionUtils.invokeStaticMethod(false, Sub.class, "staticOneArg", Integer.TYPE, 5)).isNull();
    }

    /** An exception thrown by the invoked method is reported as the cause of the exception that is thrown. */
    @Test
    public void anExceptionThrownByTheInvokedMethodIsReported() {
        final var obj = new Sub();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "throwsAnException")).isNull();
        assertThatThrownBy(() -> ReflectionUtils.invokeMethod(true, obj, "throwsAnException"))
                .isInstanceOf(IllegalArgumentException.class).getRootCause()
                .isInstanceOf(UnsupportedOperationException.class).hasMessage("thrown by the method");
    }

    /**
     * A null argument yields null, or an exception, according to the throwException argument, rather than throwing
     * a {@link NullPointerException} out of the middle of a classloader handler.
     */
    @Test
    public void nullArgumentsYieldNullOrThrow() throws NoSuchFieldException {
        final var obj = new Sub();
        final var field = Sub.class.getDeclaredField("instanceField");

        assertThat(ReflectionUtils.getFieldVal(false, null, field)).isNull();
        assertThat(ReflectionUtils.getFieldVal(false, obj, (Field) null)).isNull();
        assertThat(ReflectionUtils.getFieldVal(false, null, "instanceField")).isNull();
        assertThat(ReflectionUtils.getFieldVal(false, obj, (String) null)).isNull();
        assertThat(ReflectionUtils.getStaticFieldVal(false, null, "STATIC_FIELD")).isNull();
        assertThat(ReflectionUtils.getStaticFieldVal(false, Sub.class, null)).isNull();
        assertThat(ReflectionUtils.invokeMethod(false, null, "noArgs")).isNull();
        assertThat(ReflectionUtils.invokeMethod(false, obj, null)).isNull();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", null, "x")).isNull();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", (Class<?>[]) null, new Object[] { "x" }))
                .isNull();
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", new Class<?>[] { String.class }, null))
                .isNull();
        // The number of parameters has to match the number of parameter types
        assertThat(ReflectionUtils.invokeMethod(false, obj, "oneArg", new Class<?>[] { String.class },
                new Object[] {})).isNull();
        assertThat(ReflectionUtils.invokeStaticMethod(false, null, "staticNoArgs")).isNull();
        assertThat(ReflectionUtils.invokeStaticMethod(false, Sub.class, null)).isNull();
        assertThat(ReflectionUtils.invokeStaticMethod(false, Sub.class, "staticOneArg", null, "x")).isNull();

        // Every one of them reports the null argument rather than reading or invoking anything, when asked to throw
        assertThatThrownBy(() -> ReflectionUtils.getFieldVal(true, null, field))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.getFieldVal(true, null, "instanceField"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.getStaticFieldVal(true, null, "STATIC_FIELD"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.invokeMethod(true, null, "noArgs"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.invokeMethod(true, null, "oneArg", String.class, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.invokeMethod(true, null, "oneArg", new Class<?>[] { String.class },
                new Object[] { "x" })).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.invokeStaticMethod(true, null, "staticNoArgs"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
        assertThatThrownBy(() -> ReflectionUtils.invokeStaticMethod(true, null, "staticOneArg", String.class, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unexpected null argument");
    }

    /** A class is looked up by name, and yields null rather than throwing if it is not found. */
    @Test
    public void classesAreLookedUpByName() {
        assertThat(ReflectionUtils.classForNameOrNull("java.lang.String")).isEqualTo(String.class);
        assertThat(ReflectionUtils.classForNameOrNull("java.lang.NoSuchClassAsThis")).isNull();
    }

}
