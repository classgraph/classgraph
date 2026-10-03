package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link MethodInfo#isDefault()} returned true for every interface method with a body, including a static method,
 * which {@link java.lang.reflect.Method#isDefault()} does not report as a default method.
 */
public class MethodInfoIsDefaultTest {
    /** An interface with a method of each kind that a Java 8 interface can declare. */
    public interface InterfaceMethods {
        /** An abstract method. */
        void abstractMethod();

        /** A default method. */
        default void defaultMethod() {
        }

        /** A static method. */
        static void staticMethod() {
        }
    }

    /**
     * Only a public instance method with a body is a default method, as for
     * {@link java.lang.reflect.Method#isDefault()}.
     *
     * @throws NoSuchMethodException
     *             if reflection does not find a method.
     */
    @Test
    public void onlyAPublicInstanceMethodWithABodyIsADefaultMethod() throws NoSuchMethodException {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(InterfaceMethods.class.getName())
                .enableMethodInfo().scan()) {
            final MethodInfoList methods = scanResult.getClassInfo(InterfaceMethods.class.getName())
                    .getDeclaredMethodInfo();
            assertThat(methods.getNames()).containsExactlyInAnyOrder("abstractMethod", "defaultMethod",
                    "staticMethod");
            for (final MethodInfo method : methods) {
                final String name = method.getName();
                final boolean isDefault = InterfaceMethods.class.getMethod(name).isDefault();
                assertThat(isDefault).as(name).isEqualTo("defaultMethod".equals(name));
                assertThat(method.isDefault()).as(name).isEqualTo(isDefault);
                assertThat(method.toString().contains("default ")).as(name).isEqualTo(isDefault);
            }
        }
    }
}
