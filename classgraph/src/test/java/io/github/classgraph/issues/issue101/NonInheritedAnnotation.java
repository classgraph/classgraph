package io.github.classgraph.issues.issue101;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The Interface NonInheritedAnnotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@InheritedMetaAnnotation
public @interface NonInheritedAnnotation {
}
