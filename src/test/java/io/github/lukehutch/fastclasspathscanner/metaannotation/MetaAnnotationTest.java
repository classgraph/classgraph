package io.github.lukehutch.fastclasspathscanner.metaannotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class MetaAnnotationTest {

  @Test
  public void metaAnnotations() {
    FastClasspathScanner scan = new FastClasspathScanner(getClass().getPackage().getName()).scan();
    assertThat(scan.getNamesOfClassesWithAnnotation(MetaAnnotated.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(MetaAnnotated.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Other.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.OtherBean");

    assertThat(scan.getNamesOfClassesWithAnnotation(Other.class))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.OtherBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class.getName(), Other.class.getName()))
        .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.OtherBean",
            "io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

    assertThat(scan.getNamesOfClassesWithMetaAnnotation(Meta.class, Other.class))
    .containsExactly("io.github.lukehutch.fastclasspathscanner.metaannotation.OtherBean",
        "io.github.lukehutch.fastclasspathscanner.metaannotation.MetaAnnotatedBean");

  }

}
