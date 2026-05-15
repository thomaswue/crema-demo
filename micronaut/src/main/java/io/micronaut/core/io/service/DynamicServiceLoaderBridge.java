package io.micronaut.core.io.service;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.inject.BeanDefinitionReference;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class DynamicServiceLoaderBridge {
    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";

    private DynamicServiceLoaderBridge() {
    }

    public static void addGeneratedBeanDefinitionReferences(
            ApplicationContext context,
            ClassLoader classLoader,
            Path classesDir
    ) throws IOException {
        Path serviceDir = classesDir.resolve("META-INF").resolve("micronaut").resolve(BEAN_DEFINITION_REFERENCE);
        if (!Files.isDirectory(serviceDir)) {
            return;
        }

        List<String> generatedReferences;
        try (Stream<Path> files = Files.list(serviceDir)) {
            generatedReferences = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

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
                references.add(instantiate(classLoader, generatedReference));
            }
        }

        setBeanDefinitionReferences(context, references);
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

    private static BeanDefinitionReference instantiate(ClassLoader classLoader, String className) {
        try {
            return (BeanDefinitionReference) Class.forName(className, false, classLoader)
                    .getDeclaredConstructor()
                    .newInstance();
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
