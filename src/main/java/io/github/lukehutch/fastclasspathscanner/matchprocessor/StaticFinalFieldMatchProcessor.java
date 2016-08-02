/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.matchprocessor;

/**
 * The method to run when a class with the matching class name and with a final static field with the matching field
 * name is found on the classpath. The constant value of the final static field is obtained directly from the
 * constant pool of the classfile. See the documentation here:
 * 
 * https://github.com/lukehutch/fast-classpath-scanner/wiki/3.5.-Reading-constant-initializer-values-of-static-final-fields
 * 
 * @param className
 *            The class name, e.g. "com.package.ClassName".
 * @param fieldName
 *            The field name, e.g. "STATIC_FIELD_NAME".
 * @param fieldConstantValue
 *            The field's constant literal value, read directly from the classfile's constant pool.
 */
@FunctionalInterface
public interface StaticFinalFieldMatchProcessor {
    public void processMatch(String className, String fieldName, Object fieldConstantValue);
}