package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class MetaAnnotationTest {

  FastClasspathScanner scan = new FastClasspathScanner(getClass().getPackage().getName()).scan();

  @Test
  public void metaAnnotationsByClass() {
    assertThat(scan.getNamesOfClassesWithAnnotation(MetaAnnotated.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(MetaAnnotated.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

  }

  @Test
  public void metaAnnotationsByName() {
    assertThat(scan.getNamesOfClassesWithAnnotation(MetaAnnotated.class.getName()))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class.getName()))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(MetaAnnotated.class.getName()))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");
  }

  @Test
  public void controlWithNonMeta() {
    assertThat(scan.getNamesOfClassesWithMetaAnnotation(NonMeta.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.NonMetaBean");

    assertThat(scan.getNamesOfClassesWithAnnotation(NonMeta.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.NonMetaBean");
  }

  @Test
  public void varArgsByClass() {
    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class, NonMeta.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.NonMetaBean",
            "io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");
  }

  @Test
  public void varArgsByName() {
    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class.getName(), NonMeta.class.getName()))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.NonMetaBean",
            "io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

  }

  @Test
  public void metaMetaDontWork() {
    assertThat(NonMetaBean.class.getAnnotation(NonMeta.class)).isNotNull();
    assertThat(NonMetaBean.class.getAnnotation(MetaMetaDontWork.class)).isNull();
    assertThat(NonMetaBean.class.getAnnotationsByType(NonMeta.class)).isNotEmpty();
    assertThat(NonMetaBean.class.getAnnotationsByType(MetaMetaDontWork.class)).isEmpty();
  }

}
