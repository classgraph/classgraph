package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class MetaAnnotationTest {
    FastClasspathScanner scanner = new FastClasspathScanner(getClass().getPackage().getName()).scan();

    private static <T extends Comparable<T>> ArrayList<T> sort(List<T> list) {
        ArrayList<T> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        return sorted;
    }

    @Test
    public void metaAnnotationsByClass() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(MetaAnnotatedAnnotation.class)).containsExactly(
                MetaAnnotatedClass.class.getName());

        assertThat(sort(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class))).containsExactly(
                MetaAndNonMetaAnnotatedClass.class.getName(), MetaAnnotatedClass.class.getName());

        assertThat(sort(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotatedAnnotation.class)))
                .containsExactly(MetaAnnotatedClass.class.getName(), MetaMetaAnnotatedClass.class.getName());
    }

    @Test
    public void metaAnnotationsByName() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(MetaAnnotatedAnnotation.class.getName()))
                .containsExactly(MetaAnnotatedClass.class.getName());

        assertThat(sort(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class.getName())))
                .containsExactly(MetaAndNonMetaAnnotatedClass.class.getName(), MetaAnnotatedClass.class.getName());

        assertThat(sort(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotatedAnnotation.class.getName())))
                .containsExactly(MetaAnnotatedClass.class.getName(), MetaMetaAnnotatedClass.class.getName());
    }

    @Test
    public void nonMeta() {
        assertThat(sort(scanner.getNamesOfClassesWithMetaAnnotation(NonMetaAnnotation.class))).containsExactly(
                MetaAndNonMetaAnnotatedClass.class.getName(), NonMetaClass.class.getName());
    }

    @Test
    public void varArgsAnyOfByClass() {
        assertThat(
                sort(scanner.getNamesOfClassesWithMetaAnnotationsAnyOf(MetaAnnotation.class,
                        NonMetaAnnotation.class))) //
                .containsExactly(MetaAndNonMetaAnnotatedClass.class.getName(), MetaAnnotatedClass.class.getName(),
                        NonMetaClass.class.getName());
    }

    @Test
    public void varArgsAnyOfByName() {
        assertThat(
                sort(scanner.getNamesOfClassesWithMetaAnnotationsAnyOf(MetaAnnotation.class.getName(),
                        NonMetaAnnotation.class.getName()))) //
                .containsExactly(MetaAndNonMetaAnnotatedClass.class.getName(), MetaAnnotatedClass.class.getName(),
                        NonMetaClass.class.getName());
    }

    @Test
    public void varArgsAllOfByClass() {
        assertThat(scanner.getNamesOfClassesWithMetaAnnotationsAllOf(MetaAnnotation.class, NonMetaAnnotation.class)) //
                .containsExactly(MetaAndNonMetaAnnotatedClass.class.getName());
    }

    @Test
    public void varArgsAllOfByName() {
        assertThat(
                scanner.getNamesOfClassesWithMetaAnnotationsAllOf(MetaAnnotation.class.getName(),
                        NonMetaAnnotation.class.getName())) //
                .containsExactly(MetaAndNonMetaAnnotatedClass.class.getName());
    }

    @Test
    public void nonMetaAnnotation() {
        assertThat(NonMetaClass.class.getAnnotation(NonMetaAnnotation.class)).isNotNull();
        assertThat(NonMetaClass.class.getAnnotation(MetaAnnotation.class)).isNull();
    }

    @Test
    public void nonClassTargetDoesntWork() {
        assertThat(NonClassTarget.class.getAnnotationsByType(MetaAnnotatedAnnotation.class)).isEmpty();
        assertThat(NonClassTarget.class.getAnnotationsByType(MetaAnnotation.class)).isEmpty();
    }

    @Test
    public void metaMeta() {
        assertThat(MetaMetaAnnotatedClass.class.getAnnotation(MetaAnnotatedAnnotation.class)).isNull();
        assertThat(MetaMetaAnnotatedClass.class.getAnnotation(MetaAnnotation.class)).isNull();
    }
}
