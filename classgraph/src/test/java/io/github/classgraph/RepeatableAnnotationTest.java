package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A repeatable annotation applied more than once is stored in the classfile as a single container annotation
 * holding the repeats, rather than as several annotations. ClassGraph unwraps the container while scanning, so the
 * repeats are reported as separate annotations, and the container is not reported at all.
 */
public class RepeatableAnnotationTest {
    /** The container that holds the repeats of {@link Tag}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tags {
        /**
         * The repeats.
         *
         * @return the repeats.
         */
        Tag[] value();
    }

    /** A repeatable annotation. */
    @Repeatable(Tags.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag {
        /**
         * The name of the tag.
         *
         * @return the name of the tag.
         */
        String value();
    }

    /** An annotation that is not repeatable. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NotRepeatable {
    }

    /** A class carrying two repeats of the repeatable annotation, so the classfile holds the container. */
    @Tag("first")
    @Tag("second")
    @NotRepeatable
    public static class TaggedTwice {
        /** A method carrying two repeats of the repeatable annotation. */
        @Tag("on a method")
        @Tag("twice")
        public void method() {
        }
    }

    /** A class carrying one use of the repeatable annotation, so the classfile holds no container. */
    @Tag("only")
    public static class TaggedOnce {
    }

    /** A class carrying no repeatable annotation at all. */
    public static class NotTagged {
    }

    /** The scan of the test classes. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().acceptClasses(RepeatableAnnotationTest.class.getName() + "$*").enableAllInfo()
                .scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The annotations of a test class.
     *
     * @param cls
     *            the class.
     * @return the annotations on the class.
     */
    private static AnnotationInfoList annotationsOn(final Class<?> cls) {
        final var classInfo = scanResult.getClassInfo(cls.getName());
        assertThat(classInfo).as(cls.getName()).isNotNull();
        return classInfo.getAllAnnotationInfo().directOnly();
    }

    /** Every repeat of a repeatable annotation is reported, in the order they were applied. */
    @Test
    public void everyRepeatOfARepeatableAnnotationIsReported() {
        final var repeats = annotationsOn(TaggedTwice.class).getRepeatable(Tag.class);
        assertThat(repeats).hasSize(2);
        assertThat(repeats).extracting(ai -> ai.getParameterValues().getValue("value")).containsExactly("first",
                "second");
    }

    /** The container the compiler wraps the repeats in is an implementation detail, and is not reported. */
    @Test
    public void theContainerAnnotationIsNotReported() {
        // The two repeats stand in the list in place of the one container annotation the classfile holds
        assertThat(annotationsOn(TaggedTwice.class).getNames()).containsExactlyInAnyOrder(Tag.class.getName(),
                Tag.class.getName(), NotRepeatable.class.getName());
        assertThat(annotationsOn(TaggedTwice.class).getRepeatable(Tags.class)).isEmpty();
    }

    /**
     * A repeatable annotation applied only once is not wrapped in a container by the compiler, and is still
     * reported by {@code getRepeatable}, as a list of one.
     */
    @Test
    public void anAnnotationAppliedOnceIsStillReportedAsARepeat() {
        assertThat(annotationsOn(TaggedOnce.class).getRepeatable(Tag.class)).singleElement()
                .satisfies(ai -> assertThat(ai.getParameterValues().getValue("value")).isEqualTo("only"));
    }

    /** An annotation that is not applied at all gives the empty list, rather than null. */
    @Test
    public void anAnnotationThatIsNotAppliedGivesTheEmptyList() {
        assertThat(annotationsOn(NotTagged.class).getRepeatable(Tag.class)).isEmpty();
        // An annotation that is present, but is not the one asked for
        assertThat(annotationsOn(TaggedTwice.class).getRepeatable("com.xyz.NoSuchAnnotation")).isEmpty();
    }

    /** An annotation that is not repeatable still has one use, which is a list of one. */
    @Test
    public void anAnnotationThatIsNotRepeatableIsAListOfOne() {
        assertThat(annotationsOn(TaggedTwice.class).getRepeatable(NotRepeatable.class)).hasSize(1);
    }

    /** The repeats of an annotation on a method are reported the same way as those on a class. */
    @Test
    public void theRepeatsOfAnAnnotationOnAMethodAreReported() {
        final var method = scanResult.getClassInfo(TaggedTwice.class.getName()).getMethodInfo()
                .getSingleMethod("method");
        assertThat(method.getAllAnnotationInfo().directOnly().getRepeatable(Tag.class))
                .extracting(ai -> ai.getParameterValues().getValue("value"))
                .containsExactly("on a method", "twice");
    }

    /** Asking for a class that is not an annotation, or for nothing at all, is a programming error. */
    @Test
    public void aClassThatIsNotAnAnnotationIsRejected() {
        final var annotations = annotationsOn(TaggedTwice.class);
        @SuppressWarnings("unchecked")
        final var notAnAnnotation = (Class<? extends Annotation>) (Class<?>) String.class;
        assertThatThrownBy(() -> annotations.getRepeatable(notAnAnnotation))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annotations.getRepeatable((Class<? extends Annotation>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> annotations.getRepeatable((String) null)).isInstanceOf(NullPointerException.class);
    }
}
