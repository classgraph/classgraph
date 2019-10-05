package io.github.classgraph.issues.issue370.impl;

import io.github.classgraph.issues.issue370.annotations.ApiOperation;

/**
 * ClassWithAnnotation.
 */
public class ClassWithAnnotation {
    /**
     * Do something.
     */
    @ApiOperation(value = "", notes = "${snippetclassifications.findById}")
    public void doSomething() {
    }
}