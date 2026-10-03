package com.dalai.llama.preprod.testsupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * Builds a service through its public constructor with every dependency mocked, except the real
 * instances passed in (matched by type -- e.g. a real ObjectMapper). Lets a test of one call path
 * stub only what that path reads, instead of hand-wiring twenty constructor arguments that change
 * whenever the service gains a collaborator.
 */
public final class MockedService<T> {

    private final T instance;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    private MockedService(Class<T> type, Object... real) {
        Constructor<?> constructor = Arrays.stream(type.getConstructors())
                .max((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()))
                .orElseThrow(() -> new IllegalArgumentException(type + " has no public constructor"));
        Object[] args = Arrays.stream(constructor.getParameters()).map(parameter -> argumentFor(parameter, real)).toArray();
        try {
            instance = type.cast(constructor.newInstance(args));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not construct " + type, ex);
        }
    }

    public static <T> MockedService<T> of(Class<T> type, Object... real) {
        return new MockedService<>(type, real);
    }

    public T instance() {
        return instance;
    }

    /** The mock (or real instance) that was passed for this constructor parameter type. */
    public <D> D dependency(Class<D> type) {
        Object dependency = dependencies.get(type);
        if (dependency == null) {
            throw new IllegalArgumentException("No constructor dependency of type " + type.getSimpleName());
        }
        return type.cast(dependency);
    }

    private Object argumentFor(Parameter parameter, Object[] real) {
        Class<?> type = parameter.getType();
        Object value = Arrays.stream(real).filter(type::isInstance).findFirst().orElseGet(() -> defaultFor(type));
        dependencies.putIfAbsent(type, value);
        return value;
    }

    private static Object defaultFor(Class<?> type) {
        if (type == String.class) return "test-model";
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == double.class || type == Double.class) return 1.0;
        return mock(type);
    }
}
