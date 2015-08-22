package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import org.junit.Test;

public class MetaAnnotationTest {
    FastClasspathScanner scanner = new FastClasspathScanner(getClass().getPackage().getName()).scan();

    @Test
    public void metaAnnotationsByClass() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(MetaAnnotatedAnnotation.class)).containsExactly(
                MetaAnnotatedClass.class.getName());

        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class)).containsExactly(
                MetaAnnotatedClass.class.getName());

        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotatedAnnotation.class)).containsExactly(
                MetaAnnotatedClass.class.getName(), NonMetaClass2.class.getName());
    }

    @Test
    public void metaAnnotationsByName() {
        assertThat(scanner.getNamesOfClassesWithAnnotation(MetaAnnotatedAnnotation.class.getName()))
                .containsExactly(MetaAnnotatedClass.class.getName());

        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class.getName())).containsExactly(
                MetaAnnotatedClass.class.getName());

        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotatedAnnotation.class.getName()))
                .containsExactly(MetaAnnotatedClass.class.getName(), NonMetaClass2.class.getName());
    }

    @Test
    public void controlWithNonMeta() {
        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(NonMetaAnnotation.class)).containsExactly(
                NonMetaClass1.class.getName());

        assertThat(scanner.getNamesOfClassesWithAnnotation(NonMetaAnnotation.class)).containsExactly(
                NonMetaClass1.class.getName());
    }

    @Test
    public void varArgsByClass() {
        assertThat(scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class, NonMetaAnnotation.class))
                .containsExactly(NonMetaClass1.class.getName(), MetaAnnotatedClass.class.getName());
    }

    @Test
    public void varArgsByName() {
        assertThat(
                scanner.getNamesOfClassesWithMetaAnnotation(MetaAnnotation.class.getName(),
                        NonMetaAnnotation.class.getName())).containsExactly(NonMetaClass1.class.getName(),
                MetaAnnotatedClass.class.getName());
    }

    @Test
    public void metaMetaDoesntWork() {
        assertThat(NonMetaClass1.class.getAnnotation(NonMetaAnnotation.class)).isNotNull();
        assertThat(NonMetaClass1.class.getAnnotation(MetaMetaAnnotatedAnnotation1.class)).isNull();
        assertThat(NonMetaClass1.class.getAnnotationsByType(NonMetaAnnotation.class)).isNotEmpty();
        assertThat(NonMetaClass1.class.getAnnotationsByType(MetaMetaAnnotatedAnnotation1.class)).isEmpty();
        assertThat(NonMetaClass2.class.getAnnotation(MetaAnnotation.class)).isNull();
        assertThat(NonMetaClass2.class.getAnnotationsByType(MetaAnnotation.class)).isEmpty();
    }
}
