package io.github.classgraph.issues.issue370.impl;

import io.github.classgraph.issues.issue370.annotations.ApiOperation;

public class ClassWithAnnotation {
    @ApiOperation(value = "", notes = "${snippetclassifications.findById}")
    public void doSometing() {

    }
}