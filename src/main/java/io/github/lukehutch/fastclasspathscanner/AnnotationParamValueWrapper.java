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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner;

import java.lang.reflect.Array;

/** A union type, used for typesafe serialization/deserialization to/from JSON. Only one field is ever set. */
class AnnotationParamValueWrapper extends ScanResultObject {
    // Parameter value is split into different fields by type, so that serialization and deserialization
    // works properly (can't properly serialize a field of Object type, since the concrete type is not
    // stored in JSON).
    AnnotationEnumValue annotationEnumValue;
    AnnotationClassRef annotationClassRef;
    AnnotationInfo annotationInfo;
    AnnotationParamValueWrapper[] annotationValueArray;
    String annotationConstantString;
    Integer annotationConstantInteger;
    Long annotationConstantLong;
    Short annotationConstantShort;
    Boolean annotationConstantBoolean;
    Character annotationConstantCharacter;
    Float annotationConstantFloat;
    Double annotationConstantDouble;
    Byte annotationConstantByte;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationValueArray != null) {
            for (int i = 0; i < annotationValueArray.length; i++) {
                if (annotationValueArray[i] != null) {
                    annotationValueArray[i].setScanResult(scanResult);
                }
            }
        } else if (annotationEnumValue != null) {
            annotationEnumValue.setScanResult(scanResult);
        } else if (annotationClassRef != null) {
            annotationClassRef.setScanResult(scanResult);
        } else if (annotationInfo != null) {
            annotationInfo.setScanResult(scanResult);
        }
    }

    /** Default constructor for deserialization. */
    public AnnotationParamValueWrapper() {
    }

    public AnnotationParamValueWrapper(final Object annotationParamValue) {
        if (annotationParamValue != null) {
            if (annotationParamValue.getClass().isArray()) {
                final int n = Array.getLength(annotationParamValue);
                annotationValueArray = new AnnotationParamValueWrapper[n];
                for (int i = 0; i < n; i++) {
                    annotationValueArray[i] = new AnnotationParamValueWrapper(Array.get(annotationParamValue, i));
                }
            } else if (annotationParamValue instanceof AnnotationEnumValue) {
                annotationEnumValue = (AnnotationEnumValue) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationClassRef) {
                annotationClassRef = (AnnotationClassRef) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationInfo) {
                annotationInfo = (AnnotationInfo) annotationParamValue;
            } else if (annotationParamValue instanceof String) {
                annotationConstantString = (String) annotationParamValue;
            } else if (annotationParamValue instanceof Integer) {
                annotationConstantInteger = (Integer) annotationParamValue;
            } else if (annotationParamValue instanceof Long) {
                annotationConstantLong = (Long) annotationParamValue;
            } else if (annotationParamValue instanceof Short) {
                annotationConstantShort = (Short) annotationParamValue;
            } else if (annotationParamValue instanceof Boolean) {
                annotationConstantBoolean = (Boolean) annotationParamValue;
            } else if (annotationParamValue instanceof Character) {
                annotationConstantCharacter = (Character) annotationParamValue;
            } else if (annotationParamValue instanceof Float) {
                annotationConstantFloat = (Float) annotationParamValue;
            } else if (annotationParamValue instanceof Double) {
                annotationConstantDouble = (Double) annotationParamValue;
            } else if (annotationParamValue instanceof Byte) {
                annotationConstantByte = (Byte) annotationParamValue;
            } else {
                throw new IllegalArgumentException("Unsupported annotation parameter value type: "
                        + annotationParamValue.getClass().getName());
            }
        }
    }

    /** Unwrap the wrapped value. */
    public Object get() {
        if (annotationValueArray != null) {
            final Object[] annotationValueObjects = new Object[annotationValueArray.length];
            for (int i = 0; i < annotationValueArray.length; i++) {
                if (annotationValueArray[i] != null) {
                    annotationValueObjects[i] = annotationValueArray[i].get();
                }
            }
            return annotationValueObjects;
        } else if (annotationEnumValue != null) {
            return annotationEnumValue;
        } else if (annotationClassRef != null) {
            return annotationClassRef;
        } else if (annotationInfo != null) {
            return annotationInfo;
        } else if (annotationConstantString != null) {
            return annotationConstantString;
        } else if (annotationConstantInteger != null) {
            return annotationConstantInteger;
        } else if (annotationConstantLong != null) {
            return annotationConstantLong;
        } else if (annotationConstantShort != null) {
            return annotationConstantShort;
        } else if (annotationConstantBoolean != null) {
            return annotationConstantBoolean;
        } else if (annotationConstantCharacter != null) {
            return annotationConstantCharacter;
        } else if (annotationConstantFloat != null) {
            return annotationConstantFloat;
        } else if (annotationConstantDouble != null) {
            return annotationConstantDouble;
        } else if (annotationConstantByte != null) {
            return annotationConstantByte;
        } else {
            return null;
        }
    }
}