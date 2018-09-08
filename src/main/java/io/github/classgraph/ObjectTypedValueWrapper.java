/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
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
package io.github.classgraph;

import java.lang.reflect.Array;
import java.util.Set;

/** A union type, used for typesafe serialization/deserialization to/from JSON. Only one field is ever set. */
class ObjectTypedValueWrapper extends ScanResultObject {
    // Parameter value is split into different fields by type, so that serialization and deserialization
    // works properly (can't properly serialize a field of Object type, since the concrete type is not
    // stored in JSON).
    private AnnotationEnumValue enumValue;
    private AnnotationClassRef classRef;
    private AnnotationInfo annotationInfo;
    private ObjectTypedValueWrapper[] valueArray;
    private String stringValue;
    private Integer integerValue;
    private Long longValue;
    private Short shortValue;
    private Boolean booleanValue;
    private Character characterValue;
    private Float floatValue;
    private Double doubleValue;
    private Byte byteValue;

    /** Default constructor for deserialization. */
    public ObjectTypedValueWrapper() {
    }

    public ObjectTypedValueWrapper(final Object annotationParamValue) {
        if (annotationParamValue != null) {
            if (annotationParamValue.getClass().isArray()) {
                final int n = Array.getLength(annotationParamValue);
                valueArray = new ObjectTypedValueWrapper[n];
                for (int i = 0; i < n; i++) {
                    valueArray[i] = new ObjectTypedValueWrapper(Array.get(annotationParamValue, i));
                }
            } else if (annotationParamValue instanceof AnnotationEnumValue) {
                enumValue = (AnnotationEnumValue) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationClassRef) {
                classRef = (AnnotationClassRef) annotationParamValue;
            } else if (annotationParamValue instanceof AnnotationInfo) {
                annotationInfo = (AnnotationInfo) annotationParamValue;
            } else if (annotationParamValue instanceof String) {
                stringValue = (String) annotationParamValue;
            } else if (annotationParamValue instanceof Integer) {
                integerValue = (Integer) annotationParamValue;
            } else if (annotationParamValue instanceof Long) {
                longValue = (Long) annotationParamValue;
            } else if (annotationParamValue instanceof Short) {
                shortValue = (Short) annotationParamValue;
            } else if (annotationParamValue instanceof Boolean) {
                booleanValue = (Boolean) annotationParamValue;
            } else if (annotationParamValue instanceof Character) {
                characterValue = (Character) annotationParamValue;
            } else if (annotationParamValue instanceof Float) {
                floatValue = (Float) annotationParamValue;
            } else if (annotationParamValue instanceof Double) {
                doubleValue = (Double) annotationParamValue;
            } else if (annotationParamValue instanceof Byte) {
                byteValue = (Byte) annotationParamValue;
            } else {
                throw new IllegalArgumentException("Unsupported annotation parameter value type: "
                        + annotationParamValue.getClass().getName());
            }
        }
    }

    /**
     * @return The value wrapped by this wrapper class.
     */
    public Object get() {
        if (valueArray != null) {
            final Object[] annotationValueObjects = new Object[valueArray.length];
            for (int i = 0; i < valueArray.length; i++) {
                if (valueArray[i] != null) {
                    annotationValueObjects[i] = valueArray[i].get();
                }
            }
            return annotationValueObjects;
        } else if (enumValue != null) {
            return enumValue;
        } else if (classRef != null) {
            return classRef;
        } else if (annotationInfo != null) {
            return annotationInfo;
        } else if (stringValue != null) {
            return stringValue;
        } else if (integerValue != null) {
            return integerValue;
        } else if (longValue != null) {
            return longValue;
        } else if (shortValue != null) {
            return shortValue;
        } else if (booleanValue != null) {
            return booleanValue;
        } else if (characterValue != null) {
            return characterValue;
        } else if (floatValue != null) {
            return floatValue;
        } else if (doubleValue != null) {
            return doubleValue;
        } else if (byteValue != null) {
            return byteValue;
        } else {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (enumValue != null) {
            enumValue.setScanResult(scanResult);
        } else if (classRef != null) {
            classRef.setScanResult(scanResult);
        } else if (annotationInfo != null) {
            annotationInfo.setScanResult(scanResult);
        } else if (valueArray != null) {
            for (int i = 0; i < valueArray.length; i++) {
                if (valueArray[i] != null) {
                    valueArray[i].setScanResult(scanResult);
                }
            }
        }
    }

    /** Get the names of any classes referenced in the annotation parameters. */
    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> referencedClassNames) {
        if (enumValue != null) {
            enumValue.getClassNamesFromTypeDescriptors(referencedClassNames);
        } else if (classRef != null) {
            referencedClassNames.add(classRef.getClassName());
        } else if (annotationInfo != null) {
            annotationInfo.getClassNamesFromTypeDescriptors(referencedClassNames);
        } else if (valueArray != null) {
            for (final ObjectTypedValueWrapper item : valueArray) {
                item.getClassNamesFromTypeDescriptors(referencedClassNames);
            }
        }
    }
}