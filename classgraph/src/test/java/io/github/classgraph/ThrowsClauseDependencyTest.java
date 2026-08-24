package io.github.classgraph;

import java.io.Serial;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The exception types in a method's {@code throws} clause are dependencies of the declaring class. For a generic
 * method they are listed in the method's type signature; for a non-generic method they are only recorded in the
 * {@code Exceptions} attribute of the method, which references the exception classes through the constant pool, so
 * they are also picked up as class references while the constant pool is read.
 */
public class ThrowsClauseDependencyTest {
    /** An exception that is only referenced by a {@code throws} clause. */
    public static class ThrownException extends Exception {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * A class that references {@link ThrownException} only through a {@code throws} clause.
     */
    public static class ClassWithThrowingMethod {
        /**
         * A method that throws {@link ThrownException}.
         *
         * @throws ThrownException
         *             never thrown
         */
        public void throwingMethod() throws ThrownException {
        }
    }

    /**
     * The exception type in a {@code throws} clause is an inter-class dependency of the declaring class.
     */
    @Test
    public void throwsClauseIsInterClassDependency() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(ClassWithThrowingMethod.class.getName(), ThrownException.class.getName())
                .enableMethodInfo().enableInterClassDependencies().scan()) {
            final var classInfo = scanResult.getClassInfo(ClassWithThrowingMethod.class.getName());
            assertThat(classInfo.getClassDependencies().getNames()).contains(ThrownException.class.getName());
        }
    }
}
