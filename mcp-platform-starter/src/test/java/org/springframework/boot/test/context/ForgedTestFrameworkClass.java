package org.springframework.boot.test.context;

import java.util.function.Supplier;

/**
 * Stands for a class a server puts in Spring's test package on purpose: it is loaded from the server's own classes,
 * not from the spring-boot-test jar, so it must not make the platform believe a test framework is running.
 */
public final class ForgedTestFrameworkClass {

    private ForgedTestFrameworkClass() {
    }

    public static <T> T run(Supplier<T> action) {
        return action.get();
    }
}
