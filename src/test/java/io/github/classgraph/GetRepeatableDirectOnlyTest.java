package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationInfoList#getRepeatable(String)} copied the matching annotations into a list that treats every
 * annotation as directly present, so calling {@link AnnotationInfoList#directOnly()} on the result also returned an
 * annotation inherited from a superclass.
 */
public class GetRepeatableDirectOnlyTest {
    /** An annotation that subclasses inherit. */
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    public @interface InheritedTag {
    }

    /** A class carrying the inherited annotation. */
    @InheritedTag
    public static class InheritedTagBase {
    }

    /** A class that inherits the annotation, rather than carrying it. */
    public static class InheritsTag extends InheritedTagBase {
    }

    /** The annotations {@code getRepeatable} returns keep the record of which of them are directly present. */
    @Test
    public void anInheritedAnnotationIsNotReportedAsDirectlyPresent() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(GetRepeatableDirectOnlyTest.class.getName() + "$*").enableAnnotationInfo().scan()) {
            final AnnotationInfoList annotations = scanResult.getClassInfo(InheritsTag.class.getName())
                    .getAnnotationInfo();
            assertThat(annotations.getRepeatable(InheritedTag.class)).hasSize(1);
            assertThat(annotations.directOnly()).isEmpty();
            assertThat(annotations.getRepeatable(InheritedTag.class).directOnly()).isEmpty();
        }
    }
}
