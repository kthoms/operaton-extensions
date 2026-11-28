/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.internal.Values;

/**
 * A tool that calls one public method of a Java object (a Spring bean, a service). Only explicitly
 * registered methods are callable; the parameter types are restricted to strings, numbers, booleans
 * and enums, so the model can only pass plain values. Parameter names come from {@link P#name()} or
 * from the compiled parameter names ({@code -parameters}); descriptions from {@link P#description()}.
 * The return value is sent to the model as text ({@link Values#render(Object)}); {@code void}
 * methods answer {@code "done"}.
 *
 * <p>An exception thrown by the method fails the task (the engine retries the job and creates an
 * incident); throw {@link ToolRefusedException} to tell the model instead. Immutable and
 * thread-safe as long as the target is.
 */
public final class BeanMethodTool implements LlmTool {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final Method method;
    private final Supplier<?> target;
    private final ToolSpecification specification;
    private final List<String> parameterNames;

    /**
     * @param name tool name shown to the model ({@code [a-zA-Z0-9_-]{1,64}})
     * @param description what the tool does, shown to the model; required
     * @param targetType the class declaring the method
     * @param methodName the name of exactly one public method of {@code targetType}
     * @param target supplies the object to call, resolved on each call (allows lazy Spring beans)
     */
    public BeanMethodTool(String name, String description, Class<?> targetType, String methodName, Supplier<?> target) {
        if (name == null || !TOOL_NAME.matcher(name).matches()) {
            throw new AiLlmException("Tool name must match [a-zA-Z0-9_-]{1,64}, was '" + name + "'");
        }
        if (description == null || description.isBlank()) {
            throw new AiLlmException("Tool '" + name + "': description is required; the model chooses tools by it");
        }
        Objects.requireNonNull(targetType, "targetType");
        this.target = Objects.requireNonNull(target, "target");
        this.method = findMethod(name, targetType, methodName);

        JsonObjectSchema.Builder parameters = JsonObjectSchema.builder();
        List<String> names = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            P annotation = parameter.getAnnotation(P.class);
            String parameterName = annotation != null && !annotation.name().isBlank()
                    ? annotation.name()
                    : parameter.isNamePresent() ? parameter.getName() : null;
            if (parameterName == null) {
                throw new AiLlmException("Tool '" + name + "': parameter names of " + method
                        + " are not available; compile with -parameters or annotate with @P(name = ...)");
            }
            String parameterDescription =
                    annotation == null || annotation.description().isBlank() ? null : annotation.description();
            parameters.addProperty(parameterName, schemaFor(name, parameter.getType(), parameterDescription));
            names.add(parameterName);
            if (annotation == null
                    || annotation.required()
                    || parameter.getType().isPrimitive()) {
                required.add(parameterName);
            }
        }
        this.parameterNames = List.copyOf(names);
        ToolSpecification.Builder spec = ToolSpecification.builder().name(name).description(description);
        if (!names.isEmpty()) {
            spec.parameters(parameters.required(required).build());
        }
        this.specification = spec.build();
    }

    /** Convenience for a fixed object. */
    public static BeanMethodTool of(String name, String description, Object target, String methodName) {
        Objects.requireNonNull(target, "target");
        return new BeanMethodTool(name, description, target.getClass(), methodName, () -> target);
    }

    @Override
    public ToolSpecification specification() {
        return specification;
    }

    /** The method this tool calls. */
    public Method method() {
        return method;
    }

    @Override
    public String execute(String argumentsJson, LlmToolContext context) {
        JsonNode arguments;
        try {
            arguments = Values.JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (JsonProcessingException e) {
            throw new ToolRefusedException("The arguments are not a JSON object: " + e.getOriginalMessage());
        }
        if (!arguments.isObject()) {
            throw new ToolRefusedException("The arguments must be a JSON object");
        }
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String parameterName = parameterNames.get(i);
            JsonNode node = arguments.get(parameterName);
            Class<?> type = parameters[i].getType();
            if (node == null || node.isNull()) {
                if (type.isPrimitive() || specificationRequires(parameterName)) {
                    throw new ToolRefusedException("Argument '" + parameterName + "' is required");
                }
                values[i] = null;
                continue;
            }
            try {
                values[i] = Values.JSON.treeToValue(node, type);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new ToolRefusedException("Argument '" + parameterName + "' is not a valid " + type.getSimpleName()
                        + ": " + Values.abbreviate(node.toString(), 200));
            }
        }
        Object instance = target.get();
        if (instance == null) {
            throw new AiLlmException("Tool '" + name() + "': the target object is not available");
        }
        try {
            Object result = method.invoke(instance, values);
            return method.getReturnType() == void.class ? "done" : Values.render(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolRefusedException refused) {
                throw refused;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AiLlmException("Tool '" + name() + "' failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new AiLlmException("Tool '" + name() + "': cannot call " + method, e);
        }
    }

    private boolean specificationRequires(String parameterName) {
        return specification.parameters() != null
                && specification.parameters().required() != null
                && specification.parameters().required().contains(parameterName);
    }

    private static Method findMethod(String name, Class<?> type, String methodName) {
        List<Method> candidates = Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isBridge() && !m.isSynthetic())
                .toList();
        if (candidates.isEmpty()) {
            throw new AiLlmException(
                    "Tool '" + name + "': " + type.getName() + " has no public method '" + methodName + "'");
        }
        if (candidates.size() > 1) {
            throw new AiLlmException("Tool '" + name + "': " + type.getName() + " has " + candidates.size()
                    + " public methods named '" + methodName + "'; overloads are ambiguous, use a unique name");
        }
        return candidates.get(0);
    }

    private static JsonSchemaElement schemaFor(String tool, Class<?> type, String description) {
        if (type == String.class || type == char.class || type == Character.class) {
            return JsonStringSchema.builder().description(description).build();
        }
        if (type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == short.class
                || type == Short.class
                || type == byte.class
                || type == Byte.class
                || type == BigInteger.class) {
            return JsonIntegerSchema.builder().description(description).build();
        }
        if (type == double.class
                || type == Double.class
                || type == float.class
                || type == Float.class
                || type == BigDecimal.class) {
            return JsonNumberSchema.builder().description(description).build();
        }
        if (type == boolean.class || type == Boolean.class) {
            return JsonBooleanSchema.builder().description(description).build();
        }
        if (type.isEnum()) {
            List<String> constants = Arrays.stream(type.getEnumConstants())
                    .map(c -> ((Enum<?>) c).name())
                    .toList();
            return JsonEnumSchema.builder()
                    .enumValues(constants)
                    .description(description)
                    .build();
        }
        throw new AiLlmException("Tool '" + tool + "': parameter type " + type.getName()
                + " is not supported; use String, a number, boolean or an enum");
    }
}
