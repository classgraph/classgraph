package io.github.lukehutch.fastclasspathscanner.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ReflectionUtils {
	/** Get the named field in the given object or any of its superclasses. */
	public static Object getFieldVal(Object obj, String fieldName)
			throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
		if (obj != null) {
			for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
					classOrSuperclass = classOrSuperclass.getSuperclass()) {
				try {
					final Field field = classOrSuperclass.getDeclaredField(fieldName);
					if (!field.isAccessible()) {
						field.setAccessible(true);
					}
					return field.get(obj);
				} catch (NoSuchFieldException e) {
					// Try parent
				}
			}
		}
		return null;
	}

	/** Invoke the named method in the given object or its superclasses. */
	public static Object invokeMethod(Object obj, String methodName)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (obj != null) {
			for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
					classOrSuperclass = classOrSuperclass.getSuperclass()) {
				try {
					final Method method = classOrSuperclass.getDeclaredMethod(methodName);
					if (!method.isAccessible()) {
						method.setAccessible(true);
					}
					return method.invoke(obj);
				} catch (NoSuchMethodException e) {
					// Try parent
				}
			}
		}
		return null;
	}

	/** Invoke the named method in the given object or its superclasses. */
	public static Object invokeMethod(Object obj, String methodName, Class<?> argType, Object arg)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (obj != null) {
			for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
					classOrSuperclass = classOrSuperclass.getSuperclass()) {
				try {
					final Method method = classOrSuperclass.getDeclaredMethod(methodName, argType);
					if (!method.isAccessible()) {
						method.setAccessible(true);
					}
					return method.invoke(obj, arg);
				} catch (NoSuchMethodException e) {
					// Try parent
				}
			}
		}
		return null;
	}

	/** Invoke the named static method. */
	public static Object invokeStaticMethod(Class<?> cls, String methodName)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (cls != null) {
			try {
				final Method method = cls.getDeclaredMethod(methodName);
				if (!method.isAccessible()) {
					method.setAccessible(true);
				}
				return method.invoke(null);
			} catch (NoSuchMethodException e) {
				// Try parent
			}
		}
		return null;
	}

	/** Invoke the named static method. */
	public static Object invokeStaticMethod(Class<?> cls, String methodName, Class<?> argType, Object arg)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (cls != null) {
			try {
				final Method method = cls.getDeclaredMethod(methodName, argType);
				if (!method.isAccessible()) {
					method.setAccessible(true);
				}
				return method.invoke(null, arg);
			} catch (NoSuchMethodException e) {
				// Try parent
			}
		}
		return null;
	}
}
