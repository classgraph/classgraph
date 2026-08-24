package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ScanResult#getClassesWithAllAnnotations} intersects and
 * {@link ScanResult#getClassesWithAnyAnnotation} unions the classes found for each annotation, and that naming an
 * annotation by its class gives the same answer as naming it by its name.
 */
public class ClassesWithAnnotationsTest {
    /** A test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Alpha {
    }

    /** A second test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Beta {
    }

    /** A test annotation that is not applied to any class. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Unused {
    }

    /** A class with only the first annotation. */
    @Alpha
    public static class OnlyAlpha {
    }

    /** A class with only the second annotation. */
    @Beta
    public static class OnlyBeta {
    }

    /** A class with both annotations. */
    @Alpha
    @Beta
    public static class Both {
    }

    /** The scan result the queries are run against. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Alpha.class.getName(), Beta.class.getName(), Unused.class.getName(),
                        OnlyAlpha.class.getName(), OnlyBeta.class.getName(), Both.class.getName())
                .enableAnnotationInfo().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /** Only the classes that have every one of the named annotations are returned. */
    @Test
    public void allAnnotationsIsAnIntersection() {
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class, Beta.class).getNames())
                .containsExactly(Both.class.getName());
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class).getNames())
                .containsExactly(Both.class.getName(), OnlyAlpha.class.getName());
    }

    /** Every class that has at least one of the named annotations is returned, and only once. */
    @Test
    public void anyAnnotationIsAUnion() {
        assertThat(scanResult.getClassesWithAnyAnnotation(Alpha.class, Beta.class).getNames())
                .containsExactly(Both.class.getName(), OnlyAlpha.class.getName(), OnlyBeta.class.getName());
    }

    /** Naming an annotation by its class gives the same answer as naming it by its name. */
    @Test
    public void anAnnotationCanBeNamedByItsClassOrByItsName() {
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class, Beta.class))
                .isEqualTo(scanResult.getClassesWithAllAnnotations(Alpha.class.getName(), Beta.class.getName()));
        assertThat(scanResult.getClassesWithAnyAnnotation(Alpha.class, Beta.class))
                .isEqualTo(scanResult.getClassesWithAnyAnnotation(Alpha.class.getName(), Beta.class.getName()));
    }

    /**
     * An annotation that no class has empties the intersection, but leaves the union unchanged.
     */
    @Test
    public void anAnnotationThatNoClassHasEmptiesTheIntersectionOnly() {
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class, Unused.class)).isEmpty();
        assertThat(scanResult.getClassesWithAnyAnnotation(Alpha.class, Unused.class))
                .isEqualTo(scanResult.getClassesWithAnyAnnotation(Alpha.class));
        // The same holds for an annotation that the scan never reached at all
        final var notScanned = "com.example.NotScanned";
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class.getName(), notScanned)).isEmpty();
        assertThat(scanResult.getClassesWithAnyAnnotation(Alpha.class.getName(), notScanned))
                .isEqualTo(scanResult.getClassesWithAnyAnnotation(Alpha.class));
    }

    /** A meta-annotation matches every class that its annotations were placed on. */
    @Test
    public void anAnnotationsOwnAnnotationsMatchToo() {
        // Alpha and Beta are both annotated @Retention, so every class annotated with either has it transitively
        assertThat(scanResult.getClassesWithAnnotation(Retention.class).getNames()).contains(Both.class.getName(),
                OnlyAlpha.class.getName(), OnlyBeta.class.getName());
        assertThat(scanResult.getClassesWithAllAnnotations(Alpha.class, Retention.class).getNames())
                .isEqualTo(scanResult.getClassesWithAnnotation(Alpha.class).getNames());
    }
}
