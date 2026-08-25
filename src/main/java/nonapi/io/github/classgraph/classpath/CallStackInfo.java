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
 * Copyright (c) 2026 Luke Hutchison
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
package nonapi.io.github.classgraph.classpath;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * What a classpath search needs to know about the thread that started it: the classes in the call stack, so that
 * the caller's own classloaders and module layers are searched; the thread's context classloader; and whether the
 * thread is holding a class loading lock, which decides whether the scan can safely load classes on a worker thread
 * (#933).
 *
 * <p>
 * This has to be read on the thread that called ClassGraph, since that is the thread whose caller, whose context
 * classloader and whose locks are being asked about, and it has to be read before any of the work is handed to
 * another thread.
 */
public class CallStackInfo {
    /** The name the compiler gives a static initializer block. */
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** The methods of {@link ClassLoader} that hold the loading lock of the class that is being loaded. */
    private static final Set<String> CLASS_LOADING_METHODS = new HashSet<>(
            Arrays.asList("loadClass", "findClass", "defineClass"));

    /** The context classloader of the thread that this info was read on, or null if it has none. */
    private final ClassLoader contextClassLoader;

    /** The classes in the call stack, innermost frame first. */
    private final Class<?>[] callStack;

    /** The innermost frame that is holding a class loading lock, or null if there is no such frame. */
    private final String frameHoldingClassLoadingLock;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the context classloader of the thread that started the search.
     *
     * @return The context classloader, or null if the thread has none. This is the first classloader that a search
     *         tries, since a context classloader can be set as an override on a per-thread basis.
     */
    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    /**
     * Get the classes in the call stack.
     *
     * @return The classes in the call stack, innermost frame first.
     */
    public Class<?>[] getClassContext() {
        return callStack;
    }

    /**
     * Get the innermost stack frame that is holding a lock that the classloader would also need in order to load a
     * class on another thread.
     *
     * <p>
     * There are two such frames. A static initializer holds the initialization lock of the class it is
     * initializing, which the JVM does not release until the initializer returns. A {@link ClassLoader} method that
     * is loading a class holds that classloader's loading lock for the name of the class it is loading, and most
     * classloaders take a lock of their own as well.
     *
     * @return The stack frame that is holding the lock, in the form
     *         {@code com.xyz.Example.<clinit>(Example.java:20)}, or null if the thread that this info was read on
     *         was not holding a class loading lock. Only the innermost such frame is kept, and only so that the
     *         verbose log can say which frame stopped the scan from using worker threads.
     */
    public String getFrameHoldingClassLoadingLock() {
        return frameHoldingClassLoadingLock;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param contextClassLoader
     *            The context classloader of the thread that this info was read on, or null if it has none.
     * @param callStack
     *            The classes in the call stack, innermost frame first.
     * @param frameHoldingClassLoadingLock
     *            The innermost frame that is holding a class loading lock, or null if there is no such frame.
     */
    private CallStackInfo(final ClassLoader contextClassLoader, final Class<?>[] callStack,
            final String frameHoldingClassLoadingLock) {
        this.contextClassLoader = contextClassLoader;
        this.callStack = callStack;
        this.frameHoldingClassLoadingLock = frameHoldingClassLoadingLock;
    }

    /** What one walk of the call stack found. */
    private static class Frames {
        /** Constructor. */
        private Frames() {
        }

        /** The classes in the call stack, innermost frame first. */
        private final List<Class<?>> classes = new ArrayList<>();

        /** The innermost frame that is holding a class loading lock, or null if there is no such frame. */
        private String frameHoldingClassLoadingLock;
    }

    /**
     * Determine whether a stack frame is holding a lock that the classloader would also need in order to load a
     * class on another thread.
     *
     * @param declaringClass
     *            The class that declares the method that the stack frame is in.
     * @param methodName
     *            The name of the method that the stack frame is in.
     * @return true if the stack frame is holding a class loading lock.
     */
    private static boolean holdsClassLoadingLock(final Class<?> declaringClass, final String methodName) {
        return STATIC_INITIALIZER.equals(methodName)
                || (CLASS_LOADING_METHODS.contains(methodName) && ClassLoader.class.isAssignableFrom(declaringClass));
    }

    /**
     * Walk the call stack via the StackWalker API (JRE 9+).
     *
     * @return what the walk found, or null if the call stack could not be read.
     */
    private static Frames walkCallStackViaStackWalker() {
        try {
            //    // Implement the following via reflection, for JDK7 compatibility:
            //    StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE).forEach(sf -> { ... });

            final Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            final Frames frames = new Frames();
            final Class<?> stackWalkerOptionClass = Class.forName("java.lang.StackWalker$Option");
            final Object retainClassReference = Class.forName("java.lang.Enum")
                    .getMethod("valueOf", Class.class, String.class)
                    .invoke(null, stackWalkerOptionClass, "RETAIN_CLASS_REFERENCE");
            final Class<?> stackWalkerClass = Class.forName("java.lang.StackWalker");
            final Object stackWalkerInstance = stackWalkerClass.getMethod("getInstance", stackWalkerOptionClass)
                    .invoke(null, retainClassReference);
            final Class<?> stackFrameClass = Class.forName("java.lang.StackWalker$StackFrame");
            final Method stackFrameGetDeclaringClassMethod = stackFrameClass.getMethod("getDeclaringClass");
            final Method stackFrameGetMethodNameMethod = stackFrameClass.getMethod("getMethodName");
            stackWalkerClass.getMethod("forEach", consumerClass).invoke(stackWalkerInstance, //
                    // InvocationHandler proxy for Consumer<StackFrame>
                    Proxy.newProxyInstance(consumerClass.getClassLoader(), new Class<?>[] { consumerClass },
                            new InvocationHandler() {
                                @Override
                                public Object invoke(final Object proxy, final Method method, final Object[] args)
                                        throws Throwable {
                                    // Consumer<StackFrame> has only one method: void accept(StackFrame)
                                    final Object stackFrame = args[0];
                                    final Class<?> declaringClass = (Class<?>) stackFrameGetDeclaringClassMethod
                                            .invoke(stackFrame);
                                    frames.classes.add(declaringClass);
                                    if (frames.frameHoldingClassLoadingLock == null) {
                                        final String methodName = (String) stackFrameGetMethodNameMethod
                                                .invoke(stackFrame);
                                        if (holdsClassLoadingLock(declaringClass, methodName)) {
                                            // The innermost such frame is the one that is reported
                                            frames.frameHoldingClassLoadingLock = stackFrame.toString();
                                        }
                                    }
                                    return null;
                                }
                            }));
            return frames;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the call stack via the SecurityManager.getClassContext() native method.
     *
     * @param log
     *            the log
     * @return the call stack.
     */
    private static Class<?>[] getCallStackViaSecurityManager(final LogNode log) {
        try {
            // Call method via reflection, since SecurityManager is deprecated in JDK 17.
            final Class<?> securityManagerClass = Class.forName("java.lang.SecurityManager");
            Object securityManager = null;
            for (final Constructor<?> constructor : securityManagerClass.getDeclaredConstructors()) {
                if (constructor.getParameterTypes().length == 0) {
                    securityManager = constructor.newInstance();
                    break;
                }
            }
            if (securityManager != null) {
                final Method getClassContext = securityManager.getClass().getDeclaredMethod("getClassContext");
                getClassContext.setAccessible(true);
                return (Class<?>[]) getClassContext.invoke(securityManager);
            } else {
                return null;
            }
        } catch (final Throwable t) {
            // Creating a SecurityManager can fail if the current SecurityManager does not allow
            // RuntimePermission("createSecurityManager")
            if (log != null) {
                log.log("Exception while trying to obtain call stack via SecurityManager", t);
            }
            return null;
        }
    }

    /**
     * Find the innermost frame that is holding a class loading lock, for the JREs where the call stack is read by a
     * method that gives the classes in the stack but not the name of the method in each frame.
     *
     * <p>
     * The method names come from {@link Thread#getStackTrace()}, which gives the name of the class that declares
     * each method but not the {@link Class} itself, and resolving a class by name while a class loading lock is
     * held is exactly what must not be done here. The classes that were read from the call stack are used instead:
     * every class that declares a frame of this stack is one of them, so a frame's class can be identified by
     * matching its name, without loading anything.
     *
     * @param callStack
     *            The classes in the call stack.
     * @return the innermost frame that is holding a class loading lock, or null if there is none.
     */
    private static String findFrameHoldingClassLoadingLock(final Class<?>[] callStack) {
        StackTraceElement[] stackTrace;
        try {
            stackTrace = Thread.currentThread().getStackTrace();
        } catch (final SecurityException e) {
            return null;
        }
        for (final StackTraceElement elt : stackTrace) {
            final String methodName = elt.getMethodName();
            if (STATIC_INITIALIZER.equals(methodName)) {
                return elt.toString();
            }
            if (CLASS_LOADING_METHODS.contains(methodName)) {
                for (final Class<?> stackClass : callStack) {
                    if (stackClass.getName().equals(elt.getClassName())
                            && ClassLoader.class.isAssignableFrom(stackClass)) {
                        return elt.toString();
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read what a classpath search needs to know about the current thread.
     *
     * @param reflectionUtils
     *            the reflection utils instance
     * @param log
     *            the log
     * @return the call stack info.
     */
    public static CallStackInfo read(final ReflectionUtils reflectionUtils, final LogNode log) {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Class<?>[] callStack = null;
        String frameHoldingClassLoadingLock = null;
        boolean checkedForClassLoadingLock = false;

        // For JRE 9+, use StackWalker to get call stack.
        if (VersionFinder.JAVA_MAJOR_VERSION == 9 //
                || VersionFinder.JAVA_MAJOR_VERSION == 10 //
                || (VersionFinder.JAVA_MAJOR_VERSION == 11 //
                        && VersionFinder.JAVA_MINOR_VERSION == 0
                        && (VersionFinder.JAVA_SUB_VERSION < 4
                                || (VersionFinder.JAVA_SUB_VERSION == 4 && VersionFinder.JAVA_IS_EA_VERSION)))
                || (VersionFinder.JAVA_MAJOR_VERSION == 12 && VersionFinder.JAVA_MINOR_VERSION == 0
                        && (VersionFinder.JAVA_SUB_VERSION < 2
                                || (VersionFinder.JAVA_SUB_VERSION == 2 && VersionFinder.JAVA_IS_EA_VERSION)))) {
            // Don't trigger the StackWalker bug that crashed the JVM, which was fixed in JDK 13,
            // and backported to 12.0.2 and 11.0.4 (probably introduced in JDK 9, when StackWalker
            // was introduced):
            // https://github.com/classgraph/classgraph/issues/341
            // https://bugs.openjdk.java.net/browse/JDK-8210457
            // -- fall through
        } else {
            // Get the stack via StackWalker.
            // Invoke with doPrivileged -- see:
            // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013974.html
            try {
                final Frames frames = reflectionUtils.doPrivileged(new Callable<Frames>() {
                    @Override
                    public Frames call() throws Exception {
                        return walkCallStackViaStackWalker();
                    }
                });
                if (frames != null && !frames.classes.isEmpty()) {
                    callStack = frames.classes.toArray(new Class<?>[0]);
                    // The lock check runs in the same walk, since walking the stack is not free
                    frameHoldingClassLoadingLock = frames.frameHoldingClassLoadingLock;
                    checkedForClassLoadingLock = true;
                }
            } catch (final Throwable e) {
                // Fall through
            }
        }

        // For JRE 7 and 8, use SecurityManager to get call stack (don't use this method on JDK 9+,
        // because it will result in a reflective illegal access warning, see #663)
        if (VersionFinder.JAVA_MAJOR_VERSION < 9 && (callStack == null || callStack.length == 0)) {
            try {
                callStack = reflectionUtils.doPrivileged(new Callable<Class<?>[]>() {
                    @Override
                    public Class<?>[] call() throws Exception {
                        return getCallStackViaSecurityManager(log);
                    }
                });
            } catch (final Throwable e) {
                // Fall through
            }
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        if (callStack == null || callStack.length == 0) {
            StackTraceElement[] stackTrace = null;
            try {
                stackTrace = Thread.currentThread().getStackTrace();
            } catch (final SecurityException e) {
                // Fall through
            }
            if (stackTrace == null || stackTrace.length == 0) {
                try {
                    // Try getting stacktrace by throwing an exception
                    throw new Exception();
                } catch (final Exception e) {
                    stackTrace = e.getStackTrace();
                }
            }
            final List<Class<?>> stackClassesList = new ArrayList<>();
            for (final StackTraceElement elt : stackTrace) {
                try {
                    stackClassesList.add(Class.forName(elt.getClassName()));
                } catch (final ClassNotFoundException | LinkageError ignored) {
                    // Ignored
                }
            }
            if (!stackClassesList.isEmpty()) {
                callStack = stackClassesList.toArray(new Class<?>[0]);
            }
        }

        // Last-ditch effort -- include just this class in the call stack
        if (callStack == null || callStack.length == 0) {
            callStack = new Class<?>[] { CallStackInfo.class };
        }

        // The StackWalker is the only way of reading the stack that gives both the class and the method name of
        // each frame, so on the JREs that it is not used on, the method names have to be read from a second walk
        // of the stack, and matched up with the classes that were read from the first
        if (!checkedForClassLoadingLock) {
            frameHoldingClassLoadingLock = findFrameHoldingClassLoadingLock(callStack);
        }

        return new CallStackInfo(contextClassLoader, callStack, frameHoldingClassLoadingLock);
    }
}
