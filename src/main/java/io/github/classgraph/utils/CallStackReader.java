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
package io.github.classgraph.utils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

/** A class to find the unique ordered classpath elements. */
class CallStackReader {

    /** Get the call stack via the StackWalker API (JRE 9+). */
    private static Class<?>[] getCallStackViaStackWalker(final LogNode log) throws Exception {
        //    // Implement the following via reflection, for JDK7 compatibility:
        //    List<Class<?>> stackFrameClasses = new ArrayList<>();
        //    StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)
        //            .forEach(sf -> stackFrameClasses.add(sf.getDeclaringClass()));

        final Class<?> consumerClass = Class.forName("java.util.function.Consumer");
        final List<Class<?>> stackFrameClasses = new ArrayList<>();
        final Class<?> stackWalkerOptionClass = Class.forName("java.lang.StackWalker$Option");
        final Object RETAIN_CLASS_REFERENCE = Class.forName("java.lang.Enum")
                .getMethod("valueOf", Class.class, String.class)
                .invoke(null, stackWalkerOptionClass, "RETAIN_CLASS_REFERENCE");
        final Class<?> stackWalkerClass = Class.forName("java.lang.StackWalker");
        final Object stackWalkerInstance = stackWalkerClass.getMethod("getInstance", stackWalkerOptionClass)
                .invoke(null, RETAIN_CLASS_REFERENCE);
        final Method stackFrameGetDeclaringClassMethod = Class.forName("java.lang.StackWalker$StackFrame")
                .getMethod("getDeclaringClass");
        stackWalkerClass.getMethod("forEach", consumerClass).invoke(stackWalkerInstance, //
                // InvocationHandler proxy for Consumer<StackFrame>
                Proxy.newProxyInstance(consumerClass.getClassLoader(), new Class[] { consumerClass },
                        new InvocationHandler() {
                            @Override
                            public Object invoke(final Object proxy, final Method method, final Object[] args)
                                    throws Throwable {
                                // Consumer<StackFrame> has only one method: void accept(StackFrame)
                                final Class<?> declaringClass = (Class<?>) stackFrameGetDeclaringClassMethod
                                        .invoke(args[0]);
                                stackFrameClasses.add(declaringClass);
                                return null;
                            }
                        }));
        return stackFrameClasses.toArray(new Class<?>[0]);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
     * 
     * https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
     *
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
     */
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    /** Get the call stack via the SecurityManager API. */
    private static Class<?>[] getCallStackViaSecurityManager(final LogNode log) {
        try {
            return new CallerResolver().getClassContext();
        } catch (final Throwable e) {
            // Creating a SecurityManager can fail if the current SecurityManager does not allow
            // RuntimePermission("createSecurityManager")
            if (log != null) {
                log.log("Exception while trying to obtain call stack via SecurityManager", e);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The classes in the call stack.
     */
    static Class<?>[] getClassContext(final LogNode log) {
        // For JRE 9+, use StackWalker to get call stack
        if (VersionFinder.JAVA_MAJOR_VERSION >= 9) {
            try {
                // Invoke with doPrivileged -- see:
                // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013974.html
                return AccessController.doPrivileged(new PrivilegedAction<Class<?>[]>() {
                    @Override
                    public Class<?>[] run() {
                        try {
                            return getCallStackViaStackWalker(log);
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            } catch (final Throwable ignored) {
            }
        }

        // For JRE 7 and 8, use SecurityManager to get call stack
        try {
            return AccessController.doPrivileged(new PrivilegedAction<Class<?>[]>() {
                @Override
                public Class<?>[] run() {
                    return getCallStackViaSecurityManager(log);
                }
            });
        } catch (final Throwable ignored) {
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        try {
            throw new Exception();
        } catch (final Exception e) {
            final List<Class<?>> classes = new ArrayList<>();
            for (final StackTraceElement elt : e.getStackTrace()) {
                try {
                    classes.add(Class.forName(elt.getClassName()));
                } catch (final Throwable ignored) {
                }
            }
            if (classes.size() > 0) {
                return classes.toArray(new Class<?>[0]);
            } else {
                // Last-ditch effort -- include just this class in the call stack
                return new Class<?>[] { CallStackReader.class };
            }
        }
    }
}
