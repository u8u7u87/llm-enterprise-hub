package com.enterprise.llm.agent.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a Spring-managed Bean as an LLM Function Calling tool.
 * The method will be automatically scanned by {@link LlmToolBeanPostProcessor},
 * its JSON Schema parameters will be reflected, and it will be registered into {@link com.enterprise.llm.agent.ToolRegistry}.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmTool {

    /**
     * Unique name of the tool. If left blank, defaults to the method name.
     */
    String name() default "";

    /**
     * Detailed description of what the tool does, provided to the LLM to guide tool invocation.
     */
    String description() default "";

    /**
     * Allowed tenants. If empty, the tool is accessible to all tenants.
     */
    String[] allowedTenants() default {};

    /**
     * Security / permission scopes required to invoke this tool.
     */
    String[] scopes() default {};
}
