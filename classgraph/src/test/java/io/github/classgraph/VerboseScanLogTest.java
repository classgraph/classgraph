package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the verbose log of a scan, which is what a user reads to work out why a class was or was not found.
 */
public class VerboseScanLogTest {
    /** The logger that the verbose log is written to. */
    private static final Logger LOGGER = Logger.getLogger("io.github.classgraph.ClassGraph");

    /** A test annotation, with a parameter that has a default value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tagged {
        /**
         * The tag.
         *
         * @return the tag.
         */
        String value() default "untagged";
    }

    /** A test interface. */
    public interface Marker {
    }

    /** A test superclass. */
    public static class Base {
    }

    /** A test class with a type parameter, a superclass, an interface, an annotation, fields and methods. */
    @Tagged("container")
    public static class Container<T extends Number> extends Base implements Marker {
        /** A public field. */
        public final List<T> contents = new ArrayList<>();

        /** A package-private field, which has no modifiers to print. */
        int size;

        /**
         * A public method.
         *
         * @return the first element.
         */
        public T first() {
            return contents.get(0);
        }

        /** A package-private method, which has no modifiers to print. */
        void clear() {
            contents.clear();
            size = 0;
        }
    }

    /** The verbose log of a scan of the classes above. */
    private static String log;

    /** Scan the test classes with verbose logging on, recording the log rather than printing it. */
    @BeforeAll
    static void scanVerbosely() {
        final var logged = new StringBuilder();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logged.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
                // Nothing to flush
            }

            @Override
            public void close() {
                // Nothing to close
            }
        };
        final var useParentHandlers = LOGGER.getUseParentHandlers();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(handler);
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Tagged.class.getName(), Marker.class.getName(), Base.class.getName(),
                        Container.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().enableInterClassDependencies().verbose().scan()) {
            assertThat(scanResult.getAllClasses()).isNotEmpty();
        } finally {
            LOGGER.removeHandler(handler);
            LOGGER.setUseParentHandlers(useParentHandlers);
        }
        log = logged.toString();
    }

    /** The name of a class nested in this test class. */
    private static final String FIXTURE = VerboseScanLogTest.class.getName() + "$";

    /** Each classfile that was read is reported, and named as what kind of class it holds. */
    @Test
    public void everyClassThatWasFoundIsReported() {
        assertThat(log).contains("Found class " + FIXTURE + "Container") //
                .contains("Found class " + FIXTURE + "Base") //
                .contains("Found interface class " + FIXTURE + "Marker") //
                .contains("Found annotation class " + FIXTURE + "Tagged");
    }

    /** The supertypes of a class are reported. */
    @Test
    public void theSupertypesOfAClassAreReported() {
        assertThat(log).contains("Superclass: " + FIXTURE + "Base") //
                .contains("Interfaces: " + FIXTURE + "Marker");
    }

    /** The annotations on a class are reported, along with the default values of an annotation's own parameters. */
    @Test
    public void theAnnotationsOnAClassAreReported() {
        assertThat(log).contains("Class annotations: @" + FIXTURE + "Tagged(\"container\")") //
                .contains("Annotation default param value: value=\"untagged\"");
    }

    /** The fields and methods of a class are reported, with their modifiers. */
    @Test
    public void theFieldsAndMethodsOfAClassAreReported() {
        assertThat(log).contains("Field: public final contents") //
                .contains("Method: public first");
        // A member with no modifiers is named on its own, with no space in front of the name
        assertThat(log).contains("Field: size") //
                .contains("Method: clear");
    }

    /** The generic type signature of a class is reported. */
    @Test
    public void theTypeSignatureOfAClassIsReported() {
        assertThat(log).contains("Class type signature: <T:Ljava/lang/Number;>");
    }

    /** The classes that a class refers to, but does not extend, implement or declare, are reported. */
    @Test
    public void theOtherClassesThatAClassRefersToAreReported() {
        assertThat(log).containsPattern("Additional referenced class names: [^\n]*java\\.util\\.ArrayList");
    }
}
