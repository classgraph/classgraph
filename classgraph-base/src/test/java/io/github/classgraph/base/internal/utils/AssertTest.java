package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for the argument checks that the public API methods reject bad arguments with. The message has to name the
 * parameter that was wrong, since that is all the caller has to go on.
 */
public class AssertTest {
    /** An annotation, which is also an interface. */
    @Retention(RetentionPolicy.RUNTIME)
    private @interface AnAnnotation {
    }

    /** An interface that is not an annotation. */
    private interface AnInterface {
    }

    /** A null argument is rejected, naming the parameter it was passed as. */
    @Test
    public void aNullArgumentIsRejected() {
        assertThatThrownBy(() -> Assert.notNull(null, "name")).isInstanceOf(NullPointerException.class)
                .hasMessage("name must not be null");
        assertThatCode(() -> Assert.notNull("", "name")).doesNotThrowAnyException();
    }

    /** A null array, or a null element of an array, is rejected, naming the index of the null element. */
    @Test
    public void aNullArrayOrANullElementOfAnArrayIsRejected() {
        assertThatThrownBy(() -> Assert.notNullElements(null, "names")).isInstanceOf(NullPointerException.class)
                .hasMessage("names must not be null");
        assertThatThrownBy(() -> Assert.notNullElements(new Object[] { "a", null, "c" }, "names"))
                .isInstanceOf(NullPointerException.class).hasMessage("names[1] must not be null");
        assertThatCode(() -> Assert.notNullElements(new Object[] { "a", "b" }, "names")).doesNotThrowAnyException();

        // An empty array has no null elements
        assertThatCode(() -> Assert.notNullElements(new Object[0], "names")).doesNotThrowAnyException();
    }

    /** A class that is not an annotation is rejected, including an interface that is not an annotation. */
    @Test
    public void aClassThatIsNotAnAnnotationIsRejected() {
        assertThatCode(() -> Assert.isAnnotation(AnAnnotation.class)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Assert.isAnnotation(AnInterface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(AnInterface.class + " is not an annotation");
        assertThatThrownBy(() -> Assert.isAnnotation(String.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.class + " is not an annotation");
    }

    /** A class that is not an interface is rejected. An annotation is an interface, so it is accepted. */
    @Test
    public void aClassThatIsNotAnInterfaceIsRejected() {
        assertThatCode(() -> Assert.isInterface(List.class)).doesNotThrowAnyException();
        assertThatCode(() -> Assert.isInterface(AnAnnotation.class)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Assert.isInterface(String.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.class + " is not an interface");
    }
}
