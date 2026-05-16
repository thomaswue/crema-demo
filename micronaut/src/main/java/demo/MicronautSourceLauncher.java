package demo;

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.MixinVisitorProcessor;
import io.micronaut.annotation.processing.PackageElementVisitorProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.io.service.DynamicServiceLoaderBridge;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class MicronautSourceLauncher {
    private static final String LIB_INDEX = "launcher-libs.index";
    private static final String DEFAULT_DEPS_FILE = "deps.yml";
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );
    private static final Pattern TOP_LEVEL_CLASS_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*class\\s+([A-Za-z_$][\\w$]*)\\b"
    );

    private MicronautSourceLauncher() {
    }

    public static void main(String[] args) throws Exception {
        long processStartNanos = System.nanoTime();
        configureJavaHome();

        LaunchOptions options = LaunchOptions.parse(args);
        List<Path> sourceFiles = collectSourceFiles(options.sourcePaths());
        List<Path> testSourceFiles = options.test() ? collectSourceFiles(options.testSourcePaths()) : List.of();
        List<String> annotationPatterns = annotationPatterns(sourceFiles, options.annotationPatterns());

        Path workDir = Files.createTempDirectory("micronaut-source-launcher-");
        Path classesDir = Files.createDirectories(workDir.resolve("classes"));
        List<Path> testSupportSourceFiles = options.test() ? writeTestSupportSources(workDir) : List.of();
        StartupTimings timings = new StartupTimings(options.timings());
        timings.record("setup", processStartNanos);

        long startNanos = System.nanoTime();
        long phaseStartNanos = System.nanoTime();
        List<Path> dependencyClasspath = dependencyClasspath(options);
        timings.record("resolve deps.yml", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        InMemoryClassPath launcherClasspath = InMemoryClassPath.fromLauncherResources();
        timings.record("index launcher libs", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        compileApplicationAndTests(sourceFiles, testSourceFiles, testSupportSourceFiles, classesDir, launcherClasspath, dependencyClasspath, annotationPatterns);
        timings.record("javac and annotation processing", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        StartedServer startedServer = startServer(classesDir, dependencyClasspath, options.port(), options.properties(), timings);
        timings.record("server setup total", phaseStartNanos);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        timings.print();
        EmbeddedServer server = startedServer.server();
        System.out.println("Micronaut source launcher started " + server.getURL() + " in " + elapsedMillis + " ms");
        if (options.test()) {
            int failures = 1;
            try {
                failures = runTests(testSourceFiles, startedServer);
            } finally {
                startedServer.close();
            }
            System.exit(failures > 0 ? 1 : 0);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(startedServer::close, "micronaut-shutdown"));
        Thread.currentThread().join();
    }

    private static void configureJavaHome() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            return;
        }

        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && Files.isRegularFile(Path.of(envJavaHome, "lib", "modules"))) {
            System.setProperty("java.home", envJavaHome);
        }
    }

    private static List<String> readLibIndex(ClassLoader classLoader) throws IOException {
        try (InputStream in = classLoader.getResourceAsStream(LIB_INDEX)) {
            if (in == null) {
                throw new IOException("Missing " + LIB_INDEX + ". Run `mvn package` before launching.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .collect(Collectors.toList());
            }
        }
    }

    private static List<Path> collectSourceFiles(List<Path> sourcePaths) throws IOException {
        LinkedHashSet<Path> sourceFiles = new LinkedHashSet<>();
        for (Path sourcePath : sourcePaths) {
            Path path = sourcePath.toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                if (!path.getFileName().toString().endsWith(".java")) {
                    throw new IllegalArgumentException("Source file is not a Java file: " + path);
                }
                sourceFiles.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .forEach(sourceFiles::add);
                }
            } else {
                throw new IllegalArgumentException("Source path does not exist: " + path);
            }
        }

        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No Java source files found.");
        }
        return List.copyOf(sourceFiles);
    }

    private static List<Path> dependencyClasspath(LaunchOptions options) throws IOException {
        Optional<Path> depsFile = depsFile(options);
        if (depsFile.isEmpty()) {
            return List.of();
        }

        DependencyManifest manifest = DependencyManifest.read(depsFile.get());
        List<DependencyCoordinate> dependencies = manifest.dependencies(options.test());
        if (dependencies.isEmpty()) {
            return List.of();
        }

        return resolveDependencies(manifest, dependencies);
    }

    private static Optional<Path> depsFile(LaunchOptions options) throws IOException {
        if (options.disableDepsFile()) {
            return Optional.empty();
        }
        if (options.depsFile().isPresent()) {
            Path configured = options.depsFile().get().toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                throw new IllegalArgumentException("Dependency file does not exist: " + configured);
            }
            return Optional.of(configured);
        }

        Path root = commonSourceRoot(options.sourcePaths());
        Path candidate = root.resolve(DEFAULT_DEPS_FILE);
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static Path commonSourceRoot(List<Path> sourcePaths) throws IOException {
        Path common = null;
        for (Path sourcePath : sourcePaths) {
            Path path = sourcePath.toAbsolutePath().normalize();
            Path root;
            if (Files.isDirectory(path)) {
                root = path;
            } else if (Files.isRegularFile(path)) {
                root = path.getParent();
            } else {
                throw new IllegalArgumentException("Source path does not exist: " + path);
            }

            common = common == null ? root : commonPath(common, root);
        }
        if (common == null) {
            throw new IllegalArgumentException("No source paths provided.");
        }
        return common;
    }

    private static Path commonPath(Path left, Path right) {
        Path leftRoot = left.getRoot();
        Path rightRoot = right.getRoot();
        if (leftRoot == null || !leftRoot.equals(rightRoot)) {
            return left.toAbsolutePath().getRoot();
        }

        Path common = leftRoot;
        int max = Math.min(left.getNameCount(), right.getNameCount());
        for (int i = 0; i < max && left.getName(i).equals(right.getName(i)); i++) {
            common = common.resolve(left.getName(i));
        }
        return common;
    }

    private static List<Path> resolveDependencies(
            DependencyManifest manifest,
            List<DependencyCoordinate> dependencies
    ) {
        failOnDirectVersionConflicts(manifest.file(), dependencies);

        RepositorySystem repositorySystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newRepositorySystemSession(repositorySystem);
        List<RemoteRepository> repositories = remoteRepositories(manifest.repositories());
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        Map<String, String> selectedVersions = new HashMap<>();
        List<DependencyCoordinate> pending = new ArrayList<>(dependencies);

        for (DependencyCoordinate dependency : dependencies) {
            selectedVersions.put(dependency.key(), dependency.version());
        }

        for (int index = 0; index < pending.size(); index++) {
            DependencyCoordinate dependency = pending.get(index);
            Path jar = resolveArtifact(repositorySystem, session, repositories, dependency, "jar");
            classpath.add(jar);

            Path pom = resolveArtifact(repositorySystem, session, repositories, dependency, "pom");
            for (DependencyCoordinate transitive : readPomDependencies(pom)) {
                String selectedVersion = selectedVersions.putIfAbsent(transitive.key(), transitive.version());
                if (selectedVersion == null) {
                    pending.add(transitive);
                }
            }
        }

        return List.copyOf(classpath);
    }

    private static Path resolveArtifact(
            RepositorySystem repositorySystem,
            DefaultRepositorySystemSession session,
            List<RemoteRepository> repositories,
            DependencyCoordinate dependency,
            String extension
    ) {
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact(dependency.groupId(), dependency.artifactId(), extension, dependency.version()),
                repositories,
                null
        );
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            return result.getArtifact().getFile().toPath().toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve Maven artifact "
                    + dependency.groupId() + ":" + dependency.artifactId() + ":" + extension + ":" + dependency.version(), e);
        }
    }

    private static List<DependencyCoordinate> readPomDependencies(Path pom) {
        try (InputStream in = Files.newInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Element project = factory.newDocumentBuilder().parse(in).getDocumentElement();
            Map<String, String> properties = pomProperties(project);
            Map<String, String> managedVersions = managedVersions(project, properties);
            List<DependencyCoordinate> result = new ArrayList<>();
            Optional<Element> dependencies = directChild(project, "dependencies");
            if (dependencies.isEmpty()) {
                return List.of();
            }
            for (Element dependency : directChildren(dependencies.get(), "dependency")) {
                String scope = childText(dependency, "scope").orElse("compile");
                String optional = childText(dependency, "optional").orElse("false");
                String type = childText(dependency, "type").orElse("jar");
                if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)
                        || "import".equals(scope) || "true".equals(optional) || !"jar".equals(type)) {
                    continue;
                }
                String groupId = childText(dependency, "groupId")
                        .map(value -> interpolate(value, properties))
                        .orElseThrow(() -> new IllegalArgumentException(pom + ": Dependency is missing groupId."));
                String artifactId = childText(dependency, "artifactId")
                        .map(value -> interpolate(value, properties))
                        .orElseThrow(() -> new IllegalArgumentException(pom + ": Dependency is missing artifactId."));
                String version = childText(dependency, "version")
                        .map(value -> interpolate(value, properties))
                        .or(() -> Optional.ofNullable(managedVersions.get(groupId + ":" + artifactId)))
                        .orElseThrow(() -> new IllegalArgumentException(pom + ": Dependency is missing version: "
                                + groupId + ":" + artifactId));
                result.add(new DependencyCoordinate(groupId, artifactId, version));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read Maven POM " + pom, e);
        }
    }

    private static Map<String, String> pomProperties(Element project) {
        Map<String, String> properties = new HashMap<>();
        childText(project, "groupId").ifPresent(value -> properties.put("project.groupId", value));
        childText(project, "artifactId").ifPresent(value -> properties.put("project.artifactId", value));
        childText(project, "version").ifPresent(value -> properties.put("project.version", value));
        directChild(project, "properties").ifPresent(propertiesElement -> {
            for (Element property : directChildElements(propertiesElement)) {
                properties.put(property.getTagName(), property.getTextContent().trim());
            }
        });
        return properties;
    }

    private static Map<String, String> managedVersions(Element project, Map<String, String> properties) {
        Map<String, String> managedVersions = new HashMap<>();
        Optional<Element> dependencyManagement = directChild(project, "dependencyManagement");
        if (dependencyManagement.isEmpty()) {
            return managedVersions;
        }
        Optional<Element> dependencies = directChild(dependencyManagement.get(), "dependencies");
        if (dependencies.isEmpty()) {
            return managedVersions;
        }
        for (Element dependency : directChildren(dependencies.get(), "dependency")) {
            Optional<String> groupId = childText(dependency, "groupId").map(value -> interpolate(value, properties));
            Optional<String> artifactId = childText(dependency, "artifactId").map(value -> interpolate(value, properties));
            Optional<String> version = childText(dependency, "version").map(value -> interpolate(value, properties));
            if (groupId.isPresent() && artifactId.isPresent() && version.isPresent()) {
                managedVersions.put(groupId.get() + ":" + artifactId.get(), version.get());
            }
        }
        return managedVersions;
    }

    private static String interpolate(String value, Map<String, String> properties) {
        String result = value;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            result = result.replace("${" + property.getKey() + "}", property.getValue());
        }
        return result;
    }

    private static Optional<Element> directChild(Element parent, String tagName) {
        for (Element child : directChildElements(parent)) {
            if (child.getTagName().equals(tagName)) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        return directChildElements(parent).stream()
                .filter(child -> child.getTagName().equals(tagName))
                .toList();
    }

    private static List<Element> directChildElements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static Optional<String> childText(Element parent, String tagName) {
        return directChild(parent, tagName)
                .map(Element::getTextContent)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    private static void failOnDirectVersionConflicts(Path file, List<DependencyCoordinate> dependencies) {
        Map<String, String> versions = new HashMap<>();
        for (DependencyCoordinate dependency : dependencies) {
            String key = dependency.groupId() + ":" + dependency.artifactId();
            String previous = versions.putIfAbsent(key, dependency.version());
            if (previous != null && !previous.equals(dependency.version())) {
                throw new IllegalArgumentException(file + ": Conflicting direct dependency versions for "
                        + key + ": " + previous + " and " + dependency.version());
            }
        }
    }

    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> implementation, Throwable exception) {
                throw new IllegalStateException("Cannot initialize Maven Resolver service " + implementation.getName(), exception);
            }
        });
        RepositorySystem repositorySystem = locator.getService(RepositorySystem.class);
        if (repositorySystem == null) {
            throw new IllegalStateException("Cannot initialize Maven Resolver.");
        }
        return repositorySystem;
    }

    private static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem repositorySystem) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepository = new LocalRepository(localRepositoryPath().toString());
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepository));
        envProxySelector().ifPresent(session::setProxySelector);
        return session;
    }

    private static Path localRepositoryPath() {
        String configured = System.getProperty("maven.repo.local");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath().normalize();
    }

    private static Optional<DefaultProxySelector> envProxySelector() {
        DefaultProxySelector selector = new DefaultProxySelector();
        boolean configured = false;
        Optional<Proxy> httpProxy = proxy("http", "HTTP_PROXY")
                .or(() -> proxy("http", "http_proxy"));
        if (httpProxy.isPresent()) {
            selector.add(httpProxy.get(), nonProxyHosts());
            configured = true;
        }
        Optional<Proxy> httpsProxy = proxy("https", "HTTPS_PROXY")
                .or(() -> proxy("https", "https_proxy"));
        if (httpsProxy.isPresent()) {
            selector.add(httpsProxy.get(), nonProxyHosts());
            configured = true;
        }
        return configured ? Optional.of(selector) : Optional.empty();
    }

    private static Optional<Proxy> proxy(String type, String envName) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(value.contains("://") ? value : type + "://" + value);
            if (uri.getHost() == null) {
                return Optional.empty();
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : defaultProxyPort(type);
            Authentication authentication = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                String[] parts = userInfo.split(":", 2);
                authentication = new AuthenticationBuilder()
                        .addUsername(parts[0])
                        .addPassword(parts.length > 1 ? parts[1] : "")
                        .build();
            }
            return Optional.of(new Proxy(type, uri.getHost(), port, authentication));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int defaultProxyPort(String type) {
        return "https".equals(type) ? 443 : 80;
    }

    private static String nonProxyHosts() {
        String noProxy = Optional.ofNullable(System.getenv("NO_PROXY"))
                .orElseGet(() -> Optional.ofNullable(System.getenv("no_proxy")).orElse(""));
        if (noProxy.isBlank()) {
            return null;
        }
        return Stream.of(noProxy.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.startsWith(".") ? "*" + value : value)
                .collect(Collectors.joining("|"));
    }

    private static List<RemoteRepository> remoteRepositories(List<String> repositories) {
        List<String> configured = repositories.isEmpty() ? List.of("central") : repositories;
        Set<String> seen = new HashSet<>();
        List<RemoteRepository> result = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            String url = repositoryUrl(configured.get(i));
            if (seen.add(url)) {
                result.add(new RemoteRepository.Builder(repositoryId(url, i), "default", url).build());
            }
        }
        return List.copyOf(result);
    }

    private static String repositoryUrl(String repository) {
        return switch (repository) {
            case "central" -> "https://repo.maven.apache.org/maven2";
            default -> repository;
        };
    }

    private static String repositoryId(String repository, int index) {
        if ("https://repo.maven.apache.org/maven2".equals(repository)) {
            return "central";
        }
        return "repo-" + (index + 1);
    }

    private static List<String> annotationPatterns(List<Path> sourceFiles, List<String> configuredPatterns) throws IOException {
        if (!configuredPatterns.isEmpty()) {
            return configuredPatterns;
        }

        LinkedHashSet<String> packages = new LinkedHashSet<>();
        boolean hasDefaultPackage = false;
        for (Path sourceFile : sourceFiles) {
            Optional<String> packageName = packageName(sourceFile);
            if (packageName.isPresent()) {
                packages.add(packageName.get() + ".*");
            } else {
                hasDefaultPackage = true;
            }
        }

        if (hasDefaultPackage) {
            return List.of();
        }
        return List.copyOf(packages);
    }

    private static Optional<String> packageName(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        return packageName(source);
    }

    private static List<Path> writeTestSupportSources(Path workDir) throws IOException {
        Path sourceDir = Files.createDirectories(workDir.resolve("test-support-sources"));
        List<Path> sources = new ArrayList<>();
        sources.add(writeSource(sourceDir, "io/micronaut/test/extensions/junit5/annotation/MicronautTest.java", """
                package io.micronaut.test.extensions.junit5.annotation;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import org.junit.jupiter.api.extension.ExtendWith;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                @ExtendWith(SourceMicronautTestExtension.class)
                public @interface MicronautTest {
                }
                """));
        sources.add(writeSource(sourceDir, "io/micronaut/test/extensions/junit5/annotation/SourceMicronautTestExtension.java", """
                package io.micronaut.test.extensions.junit5.annotation;

                import io.micronaut.http.client.HttpClient;
                import io.micronaut.http.client.annotation.Client;
                import jakarta.inject.Inject;
                import java.lang.reflect.Field;
                import java.net.URI;
                import java.net.URL;
                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.extension.AfterEachCallback;
                import org.junit.jupiter.api.extension.ExtensionContext;
                import org.junit.jupiter.api.extension.ExtensionConfigurationException;
                import org.junit.jupiter.api.extension.TestInstancePostProcessor;

                final class SourceMicronautTestExtension implements TestInstancePostProcessor, AfterEachCallback {
                    private static final ExtensionContext.Namespace NAMESPACE =
                            ExtensionContext.Namespace.create(SourceMicronautTestExtension.class);

                    @Override
                    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
                        List<AutoCloseable> closeables = new ArrayList<>();
                        for (Class<?> current = testInstance.getClass(); current != null; current = current.getSuperclass()) {
                            for (Field field : current.getDeclaredFields()) {
                                if (!field.isAnnotationPresent(Inject.class)) {
                                    continue;
                                }
                                field.setAccessible(true);
                                if (HttpClient.class.isAssignableFrom(field.getType())) {
                                    HttpClient client = HttpClient.create(clientUrl(field));
                                    closeables.add(client);
                                    field.set(testInstance, client);
                                } else {
                                    throw new ExtensionConfigurationException(
                                            "Only @Inject @Client HttpClient fields are supported by the source launcher test extension: "
                                                    + field);
                                }
                            }
                        }
                        context.getStore(NAMESPACE).put(testInstance, closeables);
                    }

                    @Override
                    public void afterEach(ExtensionContext context) throws Exception {
                        Object testInstance = context.getRequiredTestInstance();
                        @SuppressWarnings("unchecked")
                        List<AutoCloseable> closeables = context.getStore(NAMESPACE).remove(testInstance, List.class);
                        if (closeables == null) {
                            return;
                        }
                        Exception failure = null;
                        for (AutoCloseable closeable : closeables) {
                            try {
                                closeable.close();
                            } catch (Exception e) {
                                if (failure == null) {
                                    failure = e;
                                } else {
                                    failure.addSuppressed(e);
                                }
                            }
                        }
                        if (failure != null) {
                            throw failure;
                        }
                    }

                    private static URL clientUrl(Field field) throws Exception {
                        String baseUrl = System.getProperty("micronaut.source.test.url");
                        if (baseUrl == null || baseUrl.isBlank()) {
                            throw new ExtensionConfigurationException("Missing micronaut.source.test.url");
                        }
                        Client client = field.getAnnotation(Client.class);
                        if (client == null || client.value().isBlank() || "/".equals(client.value())) {
                            return URI.create(baseUrl).toURL();
                        }
                        String value = client.value();
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            return URI.create(value).toURL();
                        }
                        return URI.create(baseUrl).resolve(value.startsWith("/") ? value.substring(1) : value).toURL();
                    }
                }
                """));
        return sources;
    }

    private static Path writeSource(Path sourceDir, String relativePath, String source) throws IOException {
        Path target = sourceDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
        return target;
    }

    private static void compileApplicationAndTests(
            List<Path> sourceFiles,
            List<Path> testSourceFiles,
            List<Path> testSupportSourceFiles,
            Path classesDir,
            InMemoryClassPath launcherClasspath,
            List<Path> dependencyClasspath,
            List<String> annotationPatterns
    ) throws IOException {
        compile(sourceFiles, classesDir, launcherClasspath, annotationPatterns, true, dependencyClasspath);
        if (!testSourceFiles.isEmpty()) {
            List<Path> testCompileSourceFiles = new ArrayList<>(testSupportSourceFiles.size() + testSourceFiles.size());
            testCompileSourceFiles.addAll(testSupportSourceFiles);
            testCompileSourceFiles.addAll(testSourceFiles);
            List<Path> testClassPath = new ArrayList<>(dependencyClasspath.size() + 1);
            testClassPath.add(classesDir);
            testClassPath.addAll(dependencyClasspath);
            compile(testCompileSourceFiles, classesDir, launcherClasspath, List.of(), false, testClassPath);
        }
    }

    private static void compile(
            List<Path> sourceFiles,
            Path classesDir,
            InMemoryClassPath launcherClasspath,
            List<String> annotationPatterns,
            boolean annotationProcessing,
            List<Path> additionalClassPath
    ) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler is available.");
        }

        List<String> compilerOptions = new ArrayList<>(List.of(
                "-d", classesDir.toString(),
                "-parameters",
                annotationProcessing ? "-proc:full" : "-proc:none"
        ));
        if (annotationProcessing) {
            compilerOptions.add("-Amicronaut.processing.group=demo");
            compilerOptions.add("-Amicronaut.processing.module=micronaut-source-demo");
            if (!annotationPatterns.isEmpty()) {
                compilerOptions.add("-Amicronaut.processing.annotations=" + String.join(",", annotationPatterns));
            }
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            standardFileManager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
            JavaFileManager fileManager = standardFileManager;
            standardFileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, additionalClassPath);
            fileManager = new InMemoryClassPathFileManager(standardFileManager, launcherClasspath);

            Iterable<? extends JavaFileObject> files = standardFileManager.getJavaFileObjectsFromPaths(sourceFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, compilerOptions, null, files);
            if (annotationProcessing) {
                task.setProcessors(micronautProcessors());
            }
            if (!Boolean.TRUE.equals(task.call())) {
                throw compilationFailed(diagnostics);
            }
        }
    }

    private static List<Processor> micronautProcessors() {
        return List.of(
                new MixinVisitorProcessor(),
                new PackageElementVisitorProcessor(),
                new TypeElementVisitorProcessor(),
                new AggregatingTypeElementVisitorProcessor(),
                new BeanDefinitionInjectProcessor()
        );
    }

    private static IllegalStateException compilationFailed(DiagnosticCollector<JavaFileObject> diagnostics) {
        String message = diagnostics.getDiagnostics().stream()
                .map(MicronautSourceLauncher::formatDiagnostic)
                .collect(Collectors.joining(System.lineSeparator()));
        return new IllegalStateException("Application compilation failed:" + System.lineSeparator() + message);
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        String sourceName = source == null ? "<unknown>" : source.getName();
        return sourceName + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT);
    }

    private static StartedServer startServer(
            Path classesDir,
            List<Path> dependencyClasspath,
            int port,
            Map<String, Object> launchProperties,
            StartupTimings timings
    ) throws Exception {
        long phaseStartNanos = System.nanoTime();
        List<URL> urls = new ArrayList<>(1 + dependencyClasspath.size());
        urls.add(classesDir.toUri().toURL());
        for (Path dependency : dependencyClasspath) {
            urls.add(dependency.toUri().toURL());
        }

        URLClassLoader applicationClassLoader = new URLClassLoader(urls.toArray(URL[]::new), MicronautSourceLauncher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(applicationClassLoader);
        timings.record("server classloader setup", phaseStartNanos);

        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.application.name", "micronaut-source-demo");
        properties.putAll(launchProperties);
        properties.put("micronaut.server.port", port);

        ApplicationContextBuilder builder = ApplicationContext.builder()
                .classLoader(applicationClassLoader)
                .properties(properties);
        phaseStartNanos = System.nanoTime();
        ApplicationContext context = builder.build();
        timings.record("application context build", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        DynamicServiceLoaderBridge.addGeneratedBeanDefinitionReferences(context, applicationClassLoader, classesDir);
        timings.record("load generated bean references", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        context.start();
        timings.record("application context start", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
        timings.record("embedded server start", phaseStartNanos);
        return new StartedServer(server, applicationClassLoader, context);
    }

    private static int runTests(List<Path> testSourceFiles, StartedServer startedServer) throws Exception {
        List<Class<?>> testClasses = new ArrayList<>();
        for (Path testSourceFile : testSourceFiles) {
            testClasses.add(Class.forName(binaryName(testSourceFile), true, startedServer.applicationClassLoader()));
        }

        String oldTestUrl = System.getProperty("micronaut.source.test.url");
        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(startedServer.applicationClassLoader());
        System.setProperty("micronaut.source.test.url", startedServer.server().getURL().toString());
        try {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(testClasses.stream().map(DiscoverySelectors::selectClass).toList())
                    .build();
            Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                    .enableTestEngineAutoRegistration(false)
                    .enableLauncherSessionListenerAutoRegistration(false)
                    .enableLauncherDiscoveryListenerAutoRegistration(false)
                    .enablePostDiscoveryFilterAutoRegistration(false)
                    .enableTestExecutionListenerAutoRegistration(false)
                    .addTestEngines(new JupiterTestEngine())
                    .build());
            SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
            launcher.execute(request, summaryListener, new ConsoleTestExecutionListener());
            TestExecutionSummary summary = summaryListener.getSummary();
            if (summary.getTestsFoundCount() == 0) {
                System.out.println("No source tests found.");
                return 1;
            }
            for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                failure.getException().printStackTrace(System.out);
            }
            System.out.println("Tests run: " + summary.getTestsStartedCount() + ", Failures: " + summary.getTestsFailedCount());
            return Math.toIntExact(summary.getTestsFailedCount());
        } finally {
            Thread.currentThread().setContextClassLoader(oldContextClassLoader);
            if (oldTestUrl == null) {
                System.clearProperty("micronaut.source.test.url");
            } else {
                System.setProperty("micronaut.source.test.url", oldTestUrl);
            }
        }
    }

    private static String binaryName(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        var classMatcher = TOP_LEVEL_CLASS_DECLARATION.matcher(source);
        if (!classMatcher.find()) {
            throw new IllegalArgumentException("No top-level class found in test source: " + sourceFile);
        }
        Optional<String> packageName = packageName(source);
        return packageName.map(value -> value + "." + classMatcher.group(1)).orElse(classMatcher.group(1));
    }

    private static Optional<String> packageName(String source) {
        var matcher = PACKAGE_DECLARATION.matcher(source);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private static final class ConsoleTestExecutionListener implements TestExecutionListener {
        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (!testIdentifier.isTest()) {
                return;
            }
            String status = switch (testExecutionResult.getStatus()) {
                case SUCCESSFUL -> "PASS";
                case FAILED -> "FAIL";
                case ABORTED -> "SKIP";
            };
            System.out.println(status + " " + testIdentifier.getDisplayName());
        }
    }

    private record StartedServer(EmbeddedServer server, ClassLoader applicationClassLoader, ApplicationContext applicationContext) {
        private void close() {
            server.stop();
            applicationContext.stop();
        }
    }

    private static final class StartupTimings {
        private final boolean enabled;
        private final List<String> lines = new ArrayList<>();

        private StartupTimings(boolean enabled) {
            this.enabled = enabled;
        }

        private void record(String label, long startNanos) {
            if (!enabled) {
                return;
            }
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            lines.add("  " + label + ": " + elapsedMillis + " ms");
        }

        private void print() {
            if (!enabled) {
                return;
            }
            System.out.println("Startup timings:");
            for (String line : lines) {
                System.out.println(line);
            }
        }
    }

    private static final class InMemoryClassPath {
        private final Map<String, InMemoryClassFile> byBinaryName;
        private final Map<String, List<InMemoryClassFile>> byPackageName;

        private InMemoryClassPath(Map<String, InMemoryClassFile> byBinaryName, Map<String, List<InMemoryClassFile>> byPackageName) {
            this.byBinaryName = byBinaryName;
            this.byPackageName = byPackageName;
        }

        private static InMemoryClassPath fromLauncherResources() throws IOException {
            ClassLoader classLoader = MicronautSourceLauncher.class.getClassLoader();
            Map<String, InMemoryClassFile> byBinaryName = new HashMap<>();
            Map<String, List<InMemoryClassFile>> byPackageName = new HashMap<>();
            for (String resource : readLibIndex(classLoader)) {
                try (InputStream in = classLoader.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("Missing launcher resource: " + resource);
                    }
                    indexJar(resource, in.readAllBytes(), byBinaryName, byPackageName);
                }
            }
            return new InMemoryClassPath(Map.copyOf(byBinaryName), copyPackageIndex(byPackageName));
        }

        private static void indexJar(
                String resource,
                byte[] jarBytes,
                Map<String, InMemoryClassFile> byBinaryName,
                Map<String, List<InMemoryClassFile>> byPackageName
        ) throws IOException {
            int eocdOffset = findEndOfCentralDirectory(jarBytes);
            int entries = getUnsignedShort(jarBytes, eocdOffset + 10);
            int centralDirectoryOffset = getUnsignedInt(jarBytes, eocdOffset + 16);
            int offset = centralDirectoryOffset;

            for (int i = 0; i < entries; i++) {
                if (getUnsignedInt(jarBytes, offset) != 0x02014b50) {
                    throw new IOException("Bad ZIP central directory in " + resource);
                }

                int flags = getUnsignedShort(jarBytes, offset + 8);
                int method = getUnsignedShort(jarBytes, offset + 10);
                int compressedSize = getUnsignedInt(jarBytes, offset + 20);
                int uncompressedSize = getUnsignedInt(jarBytes, offset + 24);
                int nameLength = getUnsignedShort(jarBytes, offset + 28);
                int extraLength = getUnsignedShort(jarBytes, offset + 30);
                int commentLength = getUnsignedShort(jarBytes, offset + 32);
                int localHeaderOffset = getUnsignedInt(jarBytes, offset + 42);
                String entryName = new String(jarBytes, offset + 46, nameLength, StandardCharsets.UTF_8);

                if (isClassEntry(entryName) && !byBinaryName.containsKey(binaryName(entryName))) {
                    String binaryName = binaryName(entryName);
                    int dataOffset = localDataOffset(jarBytes, localHeaderOffset);
                    InMemoryClassFile file = new InMemoryClassFile(
                            resource,
                            entryName,
                            binaryName,
                            jarBytes,
                            flags,
                            method,
                            dataOffset,
                            compressedSize,
                            uncompressedSize
                    );
                    byBinaryName.put(binaryName, file);
                    byPackageName.computeIfAbsent(packageName(binaryName), ignored -> new ArrayList<>()).add(file);
                }

                offset += 46 + nameLength + extraLength + commentLength;
            }
        }

        private static boolean isClassEntry(String entryName) {
            return entryName.endsWith(".class") &&
                    !entryName.startsWith("META-INF/versions/") &&
                    !entryName.endsWith("module-info.class");
        }

        private static String binaryName(String entryName) {
            return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
        }

        private static int localDataOffset(byte[] jarBytes, int localHeaderOffset) throws IOException {
            if (getUnsignedInt(jarBytes, localHeaderOffset) != 0x04034b50) {
                throw new IOException("Bad ZIP local header");
            }
            int nameLength = getUnsignedShort(jarBytes, localHeaderOffset + 26);
            int extraLength = getUnsignedShort(jarBytes, localHeaderOffset + 28);
            return localHeaderOffset + 30 + nameLength + extraLength;
        }

        private static int findEndOfCentralDirectory(byte[] jarBytes) throws IOException {
            int minOffset = Math.max(0, jarBytes.length - 65_557);
            for (int offset = jarBytes.length - 22; offset >= minOffset; offset--) {
                if (getUnsignedInt(jarBytes, offset) == 0x06054b50) {
                    return offset;
                }
            }
            throw new IOException("Missing ZIP end of central directory");
        }

        private static int getUnsignedShort(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
        }

        private static int getUnsignedInt(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff) |
                    ((bytes[offset + 1] & 0xff) << 8) |
                    ((bytes[offset + 2] & 0xff) << 16) |
                    ((bytes[offset + 3] & 0xff) << 24);
        }

        private static Map<String, List<InMemoryClassFile>> copyPackageIndex(Map<String, List<InMemoryClassFile>> byPackageName) {
            Map<String, List<InMemoryClassFile>> copy = new HashMap<>();
            byPackageName.forEach((packageName, files) -> copy.put(packageName, List.copyOf(files)));
            return Map.copyOf(copy);
        }

        private List<InMemoryClassFile> list(String packageName, boolean recurse) {
            if (!recurse) {
                return byPackageName.getOrDefault(packageName, List.of());
            }

            String prefix = packageName.isEmpty() ? "" : packageName + ".";
            List<InMemoryClassFile> result = new ArrayList<>();
            byPackageName.forEach((candidatePackage, files) -> {
                if (candidatePackage.equals(packageName) || candidatePackage.startsWith(prefix)) {
                    result.addAll(files);
                }
            });
            return result;
        }

        private InMemoryClassFile get(String binaryName) {
            return byBinaryName.get(binaryName);
        }

        private static String packageName(String binaryName) {
            int lastDot = binaryName.lastIndexOf('.');
            return lastDot < 0 ? "" : binaryName.substring(0, lastDot);
        }
    }

    private static final class InMemoryClassPathFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final InMemoryClassPath classPath;

        private InMemoryClassPathFileManager(StandardJavaFileManager fileManager, InMemoryClassPath classPath) {
            super(fileManager);
            this.classPath = classPath;
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, java.util.Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            Iterable<JavaFileObject> delegateFiles = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)) {
                return delegateFiles;
            }

            List<JavaFileObject> result = new ArrayList<>();
            delegateFiles.forEach(result::add);
            result.addAll(classPath.list(packageName, recurse));
            return result;
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
            if (location == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS) {
                InMemoryClassFile file = classPath.get(className);
                if (file != null) {
                    return file;
                }
            }
            return super.getJavaFileForInput(location, className, kind);
        }

        @Override
        public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
            if (location == StandardLocation.CLASS_PATH && relativeName.endsWith(".class")) {
                String simpleName = relativeName.substring(0, relativeName.length() - ".class".length()).replace('/', '.');
                String binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                InMemoryClassFile file = classPath.get(binaryName);
                if (file != null) {
                    return file;
                }
            }
            return super.getFileForInput(location, packageName, relativeName);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InMemoryClassFile inMemoryClassFile) {
                return inMemoryClassFile.binaryName();
            }
            return super.inferBinaryName(location, file);
        }
    }

    private static final class InMemoryClassFile extends SimpleJavaFileObject {
        private final String resource;
        private final String entryName;
        private final String binaryName;
        private final byte[] jarBytes;
        private final int flags;
        private final int method;
        private final int dataOffset;
        private final int compressedSize;
        private final int uncompressedSize;
        private byte[] bytes;

        private InMemoryClassFile(
                String resource,
                String entryName,
                String binaryName,
                byte[] jarBytes,
                int flags,
                int method,
                int dataOffset,
                int compressedSize,
                int uncompressedSize
        ) {
            super(URI.create("mem:///" + entryName), JavaFileObject.Kind.CLASS);
            this.resource = resource;
            this.entryName = entryName;
            this.binaryName = binaryName;
            this.jarBytes = jarBytes;
            this.flags = flags;
            this.method = method;
            this.dataOffset = dataOffset;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
        }

        private String binaryName() {
            return binaryName;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return new ByteArrayInputStream(bytes());
        }

        private byte[] bytes() throws IOException {
            if (bytes == null) {
                bytes = switch (method) {
                    case 0 -> Arrays.copyOfRange(jarBytes, dataOffset, dataOffset + compressedSize);
                    case 8 -> inflate();
                    default -> throw new IOException("Unsupported ZIP method " + method + " for " + getName());
                };
            }
            return bytes;
        }

        private byte[] inflate() throws IOException {
            if ((flags & 1) != 0) {
                throw new IOException("Encrypted ZIP entry is unsupported: " + getName());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(uncompressedSize);
            try (InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(jarBytes, dataOffset, compressedSize),
                    new Inflater(true))) {
                in.transferTo(out);
            }
            return out.toByteArray();
        }

        @Override
        public String getName() {
            return resource + "(" + entryName + ")";
        }
    }

    private record LaunchOptions(
            List<Path> sourcePaths,
            List<Path> testSourcePaths,
            int port,
            List<String> annotationPatterns,
            Map<String, Object> properties,
            boolean timings,
            boolean test,
            Optional<Path> depsFile,
            boolean disableDepsFile
    ) {
        static LaunchOptions parse(String[] args) {
            List<Path> sourcePaths = new ArrayList<>();
            List<Path> testSourcePaths = new ArrayList<>();
            int port = 8080;
            List<String> annotationPatterns = new ArrayList<>();
            Map<String, Object> properties = new HashMap<>();
            boolean timings = Boolean.getBoolean("crema.timings");
            boolean test = false;
            boolean parsingTestSources = false;
            Optional<Path> depsFile = Optional.empty();
            boolean disableDepsFile = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--" -> parsingTestSources = true;
                    case "--test" -> test = true;
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--timings" -> timings = true;
                    case "--deps-file" -> depsFile = Optional.of(Path.of(requireValue(args, ++i, arg)));
                    case "--no-deps-file" -> disableDepsFile = true;
                    case "--package" -> annotationPatterns.addAll(annotationPatterns(requireValue(args, ++i, arg)));
                    case "--property" -> addProperty(properties, requireValue(args, ++i, arg));
                    case "--help", "-h" -> usageAndExit();
                    default -> {
                        if (arg.startsWith("-D")) {
                            addProperty(properties, arg.substring(2));
                            continue;
                        }
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        if (parsingTestSources) {
                            testSourcePaths.add(Path.of(arg));
                        } else {
                            sourcePaths.add(Path.of(arg));
                        }
                    }
                }
            }

            if (sourcePaths.isEmpty() || (test && testSourcePaths.isEmpty())) {
                usageAndExit();
            }
            return new LaunchOptions(
                    List.copyOf(sourcePaths),
                    List.copyOf(testSourcePaths),
                    port,
                    List.copyOf(annotationPatterns),
                    Map.copyOf(properties),
                    timings,
                    test,
                    depsFile,
                    disableDepsFile
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static void usageAndExit() {
            System.err.println("Usage: ./micronaut [--port 8080] [--timings] [--deps-file deps.yml] [--no-deps-file] [--package demo] [--property key=value] <source.java|source-directory>...");
            System.err.println("       ./micronaut --test [--port 8080] [--deps-file deps.yml] <app-source.java|app-source-directory>... -- <test-source.java|test-source-directory>...");
            System.exit(2);
        }

        private static void addProperty(Map<String, Object> properties, String property) {
            int separator = property.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Property must use key=value syntax: " + property);
            }
            properties.put(property.substring(0, separator), property.substring(separator + 1));
        }

        private static List<String> annotationPatterns(String packages) {
            return Stream.of(packages.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> value.endsWith(".*") || value.equals("*") ? value : value + ".*")
                    .toList();
        }
    }

    private record DependencyManifest(
            Path file,
            List<String> dependencies,
            List<String> testDependencies,
            List<String> repositories
    ) {
        private static DependencyManifest read(Path file) throws IOException {
            List<String> dependencies = new ArrayList<>();
            List<String> testDependencies = new ArrayList<>();
            List<String> repositories = new ArrayList<>();
            String section = null;
            int lineNumber = 0;
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String line = stripComment(rawLine).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                    section = line.substring(0, line.length() - 1).trim();
                    switch (section) {
                        case "dependencies", "testDependencies", "repositories" -> {
                        }
                        default -> throw new IllegalArgumentException(file + ":" + lineNumber
                                + ": Unsupported dependency manifest section: " + section);
                    }
                    continue;
                }
                if (section == null || !line.startsWith("- ")) {
                    throw new IllegalArgumentException(file + ":" + lineNumber
                            + ": Expected a top-level section or list item.");
                }

                String value = unquote(line.substring(2).trim());
                if (value.isEmpty()) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": Empty dependency manifest value.");
                }
                switch (section) {
                    case "dependencies" -> dependencies.add(value);
                    case "testDependencies" -> testDependencies.add(value);
                    case "repositories" -> repositories.add(value);
                    default -> throw new IllegalStateException("Unexpected section: " + section);
                }
            }
            return new DependencyManifest(
                    file,
                    List.copyOf(dependencies),
                    List.copyOf(testDependencies),
                    List.copyOf(repositories)
            );
        }

        private List<DependencyCoordinate> dependencies(boolean includeTestDependencies) {
            List<String> selected = new ArrayList<>(dependencies);
            if (includeTestDependencies) {
                selected.addAll(testDependencies);
            }
            return selected.stream()
                    .map(value -> DependencyCoordinate.parse(file, value))
                    .toList();
        }

        private static String stripComment(String line) {
            int comment = line.indexOf('#');
            if (comment < 0) {
                return line;
            }
            if (comment == 0 || Character.isWhitespace(line.charAt(comment - 1))) {
                return line.substring(0, comment);
            }
            return line;
        }

        private static String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
        private String key() {
            return groupId + ":" + artifactId;
        }

        private static DependencyCoordinate parse(Path file, String value) {
            String[] parts = value.split(":");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalArgumentException(file + ": Dependency coordinates must use groupId:artifactId:version syntax: " + value);
            }
            return new DependencyCoordinate(parts[0], parts[1], parts[2]);
        }
    }
}
