package io.github.lukehutch.fastclasspathscanner.test.metaannotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@J
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface F {
}
