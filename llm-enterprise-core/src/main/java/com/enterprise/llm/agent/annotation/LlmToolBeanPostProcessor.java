package com.enterprise.llm.agent.annotation;

import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolHandler;
import com.enterprise.llm.agent.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Spring {@link BeanPostProcessor} that scans for methods annotated with {@link LlmTool},
 * reflects their parameter metadata into standard OpenAI Function Calling JSON Schema,
 * and dynamically registers them into {@link ToolRegistry}.
 */
@Component
public class LlmToolBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmToolBeanPostProcessor.class);

    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ObjectMapper objectMapper;

    public LlmToolBeanPostProcessor(ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(targetClass, method -> {
            LlmTool toolAnnotation = AnnotationUtils.findAnnotation(method, LlmTool.class);
            if (toolAnnotation != null) {
                registerToolMethod(bean, method, toolAnnotation);
            }
        });
        return bean;
    }

    private void registerToolMethod(Object bean, Method method, LlmTool annotation) {
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("ToolRegistry not yet available in ApplicationContext, skipping registration of @LlmTool: {}", method.getName());
            return;
        }

        String toolName = annotation.name().isBlank() ? method.getName() : annotation.name().trim();
        String description = annotation.description();

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredParams = new ArrayList<>();
        Parameter[] parameters = method.getParameters();

        for (Parameter param : parameters) {
            LlmToolParam paramAnn = param.getAnnotation(LlmToolParam.class);
            String paramName = (paramAnn != null && !paramAnn.name().isBlank())
                    ? paramAnn.name().trim()
                    : param.getName();
            String paramDesc = (paramAnn != null) ? paramAnn.description() : "";
            boolean isRequired = (paramAnn == null) || paramAnn.required();

            String jsonType = mapJavaTypeToJsonSchemaType(param.getType());
            Map<String, Object> fieldSchema = new LinkedHashMap<>();
            fieldSchema.put("type", jsonType);
            if (!paramDesc.isBlank()) {
                fieldSchema.put("description", paramDesc);
            }
            properties.put(paramName, fieldSchema);

            if (isRequired) {
                requiredParams.add(paramName);
            }
        }

        Map<String, Object> parametersSchema = new LinkedHashMap<>();
        parametersSchema.put("type", "object");
        parametersSchema.put("properties", properties);
        if (!requiredParams.isEmpty()) {
            parametersSchema.put("required", requiredParams);
        }

        ToolDefinition definition = new ToolDefinition(toolName, description, parametersSchema);

        ToolHandler handler = arguments -> {
            Object[] invokeArgs = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                LlmToolParam paramAnn = param.getAnnotation(LlmToolParam.class);
                String paramName = (paramAnn != null && !paramAnn.name().isBlank())
                        ? paramAnn.name().trim()
                        : param.getName();

                Object rawVal = (arguments != null) ? arguments.get(paramName) : null;
                invokeArgs[i] = convertValue(rawVal, param.getType());
            }

            ReflectionUtils.makeAccessible(method);
            Object result = method.invoke(bean, invokeArgs);

            if (result == null) {
                return "{\"status\": \"success\"}";
            }
            if (result instanceof String s) {
                return s;
            }
            return objectMapper.writeValueAsString(result);
        };

        Set<String> allowedTenants = (annotation.allowedTenants() != null && annotation.allowedTenants().length > 0)
                ? Set.of(annotation.allowedTenants()) : Collections.emptySet();
        Set<String> scopes = (annotation.scopes() != null && annotation.scopes().length > 0)
                ? Set.of(annotation.scopes()) : Collections.emptySet();
        registry.registerTool(definition, handler, allowedTenants, scopes);
        log.info("Registered @LlmTool [{}] from bean [{}] method [{}] (tenants: {}, scopes: {})",
                toolName, bean.getClass().getSimpleName(), method.getName(), allowedTenants, scopes);
    }

    private String mapJavaTypeToJsonSchemaType(Class<?> clazz) {
        if (clazz == String.class || clazz == Character.class || clazz == char.class) {
            return "string";
        }
        if (clazz == Integer.class || clazz == int.class
                || clazz == Long.class || clazz == long.class
                || clazz == Short.class || clazz == short.class
                || clazz == Byte.class || clazz == byte.class
                || clazz == BigInteger.class) {
            return "integer";
        }
        if (clazz == Double.class || clazz == double.class
                || clazz == Float.class || clazz == float.class
                || clazz == BigDecimal.class) {
            return "number";
        }
        if (clazz == Boolean.class || clazz == boolean.class) {
            return "boolean";
        }
        if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) {
            return "array";
        }
        return "object";
    }

    private Object convertValue(Object raw, Class<?> targetType) {
        if (raw == null) {
            if (targetType.isPrimitive()) {
                if (targetType == boolean.class) return false;
                if (targetType == int.class) return 0;
                if (targetType == long.class) return 0L;
                if (targetType == double.class) return 0.0;
                if (targetType == float.class) return 0.0f;
            }
            return null;
        }

        if (targetType.isInstance(raw)) {
            return raw;
        }

        String strVal = raw.toString();
        if (targetType == String.class) {
            return strVal;
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(strVal);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(strVal);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(strVal);
        }
        if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(strVal);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(strVal);
        }

        return objectMapper.convertValue(raw, targetType);
    }
}
