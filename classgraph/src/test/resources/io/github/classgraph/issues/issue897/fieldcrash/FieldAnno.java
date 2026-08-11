package io.github.classgraph.issues.issue897.fieldcrash;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
@Target(TYPE_USE) @Retention(RUNTIME) public @interface FieldAnno {}
