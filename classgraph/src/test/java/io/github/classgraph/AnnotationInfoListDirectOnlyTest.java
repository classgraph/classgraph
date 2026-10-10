package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationInfoList#directOnly()} returns the same list on every call, and a list whose annotations are all
 * directly present is its own {@code directOnly()} list, including the lists returned by
 * {@link AnnotationInfoList#filter}.
 */
public class AnnotationInfoListDirectOnlyTest {
    /** A meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Meta {
    }

    /** An annotation that is itself annotated with the meta-annotation. */
    @Meta
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Direct {
    }

    /** An annotation with no meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Plain {
    }

    /** A class with two direct annotations, one of which has a meta-annotation. */
    @Direct
    @Plain
    public static class Annotated {
    }

    /** The scan result. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scanTestClasses() {
        scanResult = new ClassGraph().enableClassInfo().enableClasspath().acceptClasses(Meta.class.getName(),
                Direct.class.getName(), Plain.class.getName(), Annotated.class.getName()).enableAnnotationInfo()
                .scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The annotations that can be reached from {@link Annotated}.
     *
     * @return the reachable annotations.
     */
    private static AnnotationInfoList reachableAnnotations() {
        final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
        assertThat(classInfo).isNotNull();
        final var reachable = classInfo.getAllAnnotationInfo();
        assertThat(reachable.getNames()).containsExactly(Direct.class.getName(), Meta.class.getName(),
                Plain.class.getName());
        return reachable;
    }

    /** {@code directOnly()} returns the same list on every call, and that list is its own direct list. */
    @Test
    public void directOnlyReturnsTheSameListOnEveryCall() {
        final var reachable = reachableAnnotations();
        final var direct = reachable.directOnly();
        assertThat(direct.getNames()).containsExactly(Direct.class.getName(), Plain.class.getName());
        assertThat(reachable.directOnly()).isSameAs(direct);
        assertThat(direct.directOnly()).isSameAs(direct);
    }

    /** A list built from a collection treats every annotation as direct, so it is its own direct list. */
    @Test
    public void aListOfOnlyDirectAnnotationsIsItsOwnDirectList() {
        final var copy = new AnnotationInfoList(reachableAnnotations());
        assertThat(copy.directOnly()).isSameAs(copy);
        assertThat(AnnotationInfoList.emptyList().directOnly()).isSameAs(AnnotationInfoList.emptyList());
    }

    /**
     * Filtering a list of only direct annotations gives a list of only direct annotations, and filtering a list
     * with meta-annotations keeps track of which of the kept annotations are direct.
     */
    @Test
    public void filterKeepsTrackOfWhichAnnotationsAreDirect() {
        final var reachable = reachableAnnotations();

        final var filteredDirect = reachable.directOnly().filter(ai -> true);
        assertThat(filteredDirect.getNames()).containsExactly(Direct.class.getName(), Plain.class.getName());
        assertThat(filteredDirect.directOnly()).isSameAs(filteredDirect);

        final var filteredReachable = reachable.filter(ai -> !ai.getName().equals(Plain.class.getName()));
        assertThat(filteredReachable.getNames()).containsExactly(Direct.class.getName(), Meta.class.getName());
        final var filteredReachableDirect = filteredReachable.directOnly();
        assertThat(filteredReachableDirect.getNames()).containsExactly(Direct.class.getName());
        assertThat(filteredReachable.directOnly()).isSameAs(filteredReachableDirect);
        assertThat(filteredReachableDirect.directOnly()).isSameAs(filteredReachableDirect);
    }
}
