package io.github.classgraph.features.packageannotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A package annotation that is itself annotated with {@link MetaAnnotation}. */
@MetaAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PACKAGE)
public @interface MetaAnnotatedPackageAnnotation {
}
