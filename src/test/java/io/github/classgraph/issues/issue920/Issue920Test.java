package io.github.classgraph.issues.issue920;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList;

/**
 * ClassGraph used to return incorrect modifiers for non-public constructors if
 * there is a public constructor of same signature in the superclass AND `ignoreMethodVisibility` has not been set.
 * In that case it will instead return the super's constructor's modifiers.
 */
public class Issue920Test {
    @Test
    void test() {
        MethodInfoList constructors = new ClassGraph()
                .enableAnnotationInfo()
                .enableSystemJarsAndModules()
                .enableClassInfo()
                .enableMethodInfo()
                .scan()
                .getClassInfo("java.io.ObjectOutputStream")
                .getConstructorInfo();
        for (MethodInfo constructor : constructors) {
            if (constructor.getParameterInfo().length == 0) {
                // The no args constructor of ObjectOutputStream is protected
                assertEquals(Modifier.PROTECTED, constructor.getModifiers(), "The no-args constructor of ObjectOutputStream should read as `protected`");
            }
        }
    }
}
