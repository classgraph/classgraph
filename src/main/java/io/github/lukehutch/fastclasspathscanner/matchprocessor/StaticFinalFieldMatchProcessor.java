/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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
 * constant pool of the classfile.
 * 
 * Field values are obtained directly from the constant pool in classfiles, not from a loaded class using
 * reflection. This allows you to detect changes to the classpath and then run another scan that picks up the new
 * values of selected static constants without reloading the class. (Class reloading is fraught with issues, see:
 * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
 * 
 * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values that
 * are the result of an expression or reference, except for cases where the compiler is able to simplify an
 * expression into a single constant at compiletime, such as in the case of string concatenation (see
 * https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.1 ). The following are examples of constant
 * static final fields:
 * 
 * <code>
 *   public static final int w = 5;
 *   public static final String x = "a";
 *   static final String y = "a" + "b";  // Referentially equal to the interned String object "ab"
 *   private static final int z = 1;     // Private field values are also returned 
 *   static final byte b = 0x7f;         // Primitive constants are autoboxed, e.g. byte -> Byte
 * </code>
 * 
 * whereas the following fields are non-constant assignments, so these fields cannot be matched:
 * 
 * <code>
 *   public static final Integer w = 5;  // Non-constant due to autoboxing
 *   static final String y = "a" + w;    // Non-constant expression, because x is non-constant
 *   static final int[] arr = {1, 2, 3}; // Arrays are non-constant
 *   static int n = 100;                 // Non-final 
 *   final int N = 100;                  // Non-static 
 * </code>
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