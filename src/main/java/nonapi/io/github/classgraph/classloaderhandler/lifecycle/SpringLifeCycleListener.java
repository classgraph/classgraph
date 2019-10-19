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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.classloaderhandler.lifecycle;

import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.springframework.boot.context.embedded.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Register a {@link ServletContextListener} to respond to Spring context shutdown (#376). This creates classfile
 * references to Spring, Spring-Boot, and servlet classes, however this class {@link SpringLifeCycleListener} is
 * never actually referenced by any other class in ClassGraph (it is only included in the classpath so that Spring
 * can locate it using the {@link Configuration} annotation). Therefore ClassGraph has only a compile-time
 * ("provides"-scoped) dependency on Spring to enable Spring to find this class, but a ClassNotFound exception
 * should not be thrown by anything else that uses ClassGraph.
 */
@Configuration
public class SpringLifeCycleListener {
    @Bean
    ServletListenerRegistrationBean<ServletContextListener> servletListener() {
        final ServletListenerRegistrationBean<ServletContextListener> srb = new ServletListenerRegistrationBean<>();
        srb.setListener(new ServletContextListener() {
            /** The logger. */
            private final Logger log = Logger.getLogger(ClassGraph.class.getName());

            /**
             * Context initialized.
             *
             * @param event
             *            the event
             */
            @Override
            public void contextInitialized(final ServletContextEvent event) {
                log.info("Spring container detected -- disabling ClassGraph shutdown hook");
                ClassGraph.disableShutdownHook();
            }

            /**
             * Context destroyed.
             *
             * @param event
             *            the event
             */
            @Override
            public void contextDestroyed(final ServletContextEvent event) {
                // Cleanly close down any open {@link ScanResult} instances.
                log.info("Closing any remaning open ClassGraph ScanResult instances");
                ScanResult.closeAll();
            }
        });
        return srb;
    }
}
