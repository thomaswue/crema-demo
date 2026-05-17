package io.micronaut.core.io.service;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.inject.BeanDefinitionReference;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class DynamicServiceLoaderBridge {
    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";
    private static final String BEAN_INTROSPECTION_REFERENCE = "io.micronaut.core.beans.BeanIntrospectionReference";
    private static Map<String, Object> sourceLauncherTestProperties = Map.of();
    private static PropertySource[] sourceLauncherTestPropertySources = new PropertySource[0];

    private DynamicServiceLoaderBridge() {
    }

    public interface GeneratedServiceProvider {
        List<String> generatedMicronautServiceClassNames(String serviceName);
    }

    public static void addGeneratedBeanDefinitionReferences(
            ApplicationContext context,
            ClassLoader classLoader,
            Path classesDir
    ) throws IOException {
        addGeneratedBeanReferences(context, classLoader, generatedServiceClassNames(classesDir, BEAN_DEFINITION_REFERENCE));
        addGeneratedIntrospectionReferences(classLoader, generatedServiceClassNames(classesDir, BEAN_INTROSPECTION_REFERENCE));
    }

    public static void addGeneratedBeanDefinitionReferences(
            ApplicationContext context,
            ClassLoader classLoader
    ) throws IOException {
        addGeneratedBeanReferences(context, classLoader, generatedServiceClassNames(classLoader, BEAN_DEFINITION_REFERENCE));
        addGeneratedIntrospectionReferences(classLoader, generatedServiceClassNames(classLoader, BEAN_INTROSPECTION_REFERENCE));
    }

    public static void configureSourceLauncherTestProperties(PropertySource[] propertySources, Map<String, Object> properties) {
        sourceLauncherTestPropertySources = propertySources.clone();
        sourceLauncherTestProperties = Map.copyOf(properties);
    }

    public static Map<String, Object> sourceLauncherTestProperties() {
        return sourceLauncherTestProperties;
    }

    public static PropertySource[] sourceLauncherTestPropertySources() {
        return sourceLauncherTestPropertySources.clone();
    }

    public static void clearSourceLauncherTestProperties() {
        sourceLauncherTestPropertySources = new PropertySource[0];
        sourceLauncherTestProperties = Map.of();
    }

    private static void addGeneratedBeanReferences(
            ApplicationContext context,
            ClassLoader classLoader,
            List<String> generatedReferences
    ) throws IOException {
        if (generatedReferences.isEmpty()) {
            return;
        }

        List<BeanDefinitionReference> references = new ArrayList<>(
                MicronautMetaServiceLoaderUtils.findMetaMicronautServiceEntries(
                        classLoader,
                        BeanDefinitionReference.class,
                        null
                )
        );
        Set<String> seenReferences = new HashSet<>();
        for (BeanDefinitionReference reference : references) {
            seenReferences.add(reference.getClass().getName());
        }
        for (String generatedReference : generatedReferences) {
            if (seenReferences.add(generatedReference)) {
                references.add(instantiate(classLoader, generatedReference, BeanDefinitionReference.class));
            }
        }

        setBeanDefinitionReferences(context, references);
    }

    private static void addGeneratedIntrospectionReferences(ClassLoader classLoader, List<String> generatedReferences) throws IOException {
        if (generatedReferences.isEmpty()) {
            return;
        }

        List<BeanIntrospectionReference> references = new ArrayList<>(
                MicronautMetaServiceLoaderUtils.findMetaMicronautServiceEntries(
                        classLoader,
                        BeanIntrospectionReference.class,
                        null
                )
        );
        Set<String> seenReferences = new HashSet<>();
        for (BeanIntrospectionReference reference : references) {
            seenReferences.add(reference.getClass().getName());
        }
        for (String generatedReference : generatedReferences) {
            if (seenReferences.add(generatedReference)) {
                references.add(instantiate(classLoader, generatedReference, BeanIntrospectionReference.class));
            }
        }

        setBeanIntrospectionReferences(references);
    }

    private static List<String> generatedServiceClassNames(ClassLoader classLoader, String serviceName) {
        if (classLoader instanceof GeneratedServiceProvider provider) {
            return provider.generatedMicronautServiceClassNames(serviceName);
        }
        return List.of();
    }

    private static List<String> generatedServiceClassNames(Path classesDir, String serviceName) throws IOException {
        Path serviceDir = classesDir.resolve("META-INF").resolve("micronaut").resolve(serviceName);
        if (!Files.isDirectory(serviceDir)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(serviceDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static void setBeanDefinitionReferences(
            ApplicationContext context,
            List<BeanDefinitionReference> references
    ) {
        try {
            Field field = DefaultBeanContext.class.getDeclaredField("beanDefinitionReferences");
            field.setAccessible(true);
            field.set(context, references);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot install dynamic Micronaut bean definitions", e);
        }
    }

    private static void setBeanIntrospectionReferences(List<BeanIntrospectionReference> references) {
        try {
            Map<String, BeanIntrospectionReference<?>> introspections = new HashMap<>();
            for (BeanIntrospectionReference<?> reference : references) {
                introspections.put(reference.getName(), reference);
            }

            Field field = BeanIntrospector.SHARED.getClass().getDeclaredField("introspectionMap");
            field.setAccessible(true);
            field.set(BeanIntrospector.SHARED, introspections);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot install dynamic Micronaut bean introspections", e);
        }
    }

    private static <T> T instantiate(ClassLoader classLoader, String className, Class<T> type) {
        try {
            return type.cast(Class.forName(className, false, classLoader)
                    .getDeclaredConstructor()
                    .newInstance());
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot load generated Micronaut service: " + className, e);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (target instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot instantiate generated Micronaut service: " + className, target);
        }
    }
}
