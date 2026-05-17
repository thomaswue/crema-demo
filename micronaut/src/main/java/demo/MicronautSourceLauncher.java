package demo;

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.MixinVisitorProcessor;
import io.micronaut.annotation.processing.PackageElementVisitorProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class MicronautSourceLauncher {
    private static final String LIB_INDEX = "launcher-libs.index";
    private static final List<String> APPLICATION_CONFIG_FILES = List.of("application.yml", "application.yaml");
    private static final String SOURCE_LAUNCHER_CONTEXT_BUILDER = "io.crema.micronaut.test.SourceLauncherContextBuilder";
    private static final String MICRONAUT_TEST_DESCRIPTOR = "Lio/micronaut/test/extensions/junit5/annotation/MicronautTest;";
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
        List<ResourceFile> resourceFiles = collectResourceFiles(options.sourcePaths());
        List<ApplicationConfig> applicationConfigs = applicationConfigs(resourceFiles);
        List<ResourceFile> classpathResourceFiles = classpathResourceFiles(resourceFiles);
        List<String> annotationPatterns = annotationPatterns(sourceFiles, options.annotationPatterns());
        List<String> testAnnotationPatterns = options.test()
                ? annotationPatterns(concat(sourceFiles, testSourceFiles), options.annotationPatterns())
                : List.of();

        List<JavaFileObject> testSupportSourceFiles = options.test() ? testSupportSources() : List.of();
        StartupTimings timings = new StartupTimings(options.timings());
        timings.record("setup", processStartNanos);

        long startNanos = System.nanoTime();
        long phaseStartNanos = System.nanoTime();
        List<Path> dependencyClasspath = dependencyClasspath(options, applicationConfigs);
        timings.record("resolve dependencies", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        InMemoryClassPath launcherClasspath = InMemoryClassPath.fromLauncherResources(options.test());
        timings.record("index launcher libs", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        InMemoryCompilationOutput compiledOutput = compileApplicationAndTests(
                sourceFiles,
                testSourceFiles,
                testSupportSourceFiles,
                launcherClasspath,
                dependencyClasspath,
                annotationPatterns,
                testAnnotationPatterns,
                classpathResourceFiles
        );
        timings.record("javac and annotation processing", phaseStartNanos);
        loadBuiltInJdbcDrivers(applicationConfigs, classpathResourceFiles, options.properties());
        if (options.test()) {
            int failures = runTests(testSourceFiles, compiledOutput, dependencyClasspath, applicationConfigs, options.port(), options.properties(), timings);
            timings.print();
            System.exit(failures > 0 ? 1 : 0);
            return;
        }

        phaseStartNanos = System.nanoTime();
        StartedServer startedServer = startServer(compiledOutput, dependencyClasspath, applicationConfigs, options.port(), options.properties(), timings);
        timings.record("server setup total", phaseStartNanos);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        timings.print();
        EmbeddedServer server = startedServer.server();
        System.out.println("Micronaut source launcher started " + server.getURL() + " in " + elapsedMillis + " ms");
        Runtime.getRuntime().addShutdownHook(new Thread(startedServer::close, "micronaut-shutdown"));
        Thread.currentThread().join();
    }

    private static List<Path> concat(List<Path> first, List<Path> second) {
        List<Path> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return List.copyOf(result);
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

    private static List<ResourceFile> collectResourceFiles(List<Path> sourcePaths) throws IOException {
        Map<String, ResourceFile> resources = new LinkedHashMap<>();
        for (Path sourcePath : sourcePaths) {
            Path path = sourcePath.toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                collectSiblingApplicationResource(path, resources);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        if (isSourceResource(file)) {
                            putResource(resources, resourceName(path, file), file);
                        }
                    }
                }
            } else {
                throw new IllegalArgumentException("Source path does not exist: " + path);
            }
        }
        return List.copyOf(resources.values());
    }

    private static void collectSiblingApplicationResource(Path sourceFile, Map<String, ResourceFile> resources) {
        Path parent = sourceFile.getParent();
        if (parent == null) {
            return;
        }
        for (String fileName : List.of("application.yml", "application.yaml", "application.properties", "application.json")) {
            Path resource = parent.resolve(fileName);
            if (Files.isRegularFile(resource)) {
                putResource(resources, fileName, resource);
            }
        }
    }

    private static boolean isSourceResource(Path file) {
        String fileName = file.getFileName().toString();
        return !fileName.endsWith(".java");
    }

    private static String resourceName(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static void putResource(Map<String, ResourceFile> resources, String resourceName, Path file) {
        ResourceFile previous = resources.putIfAbsent(resourceName, new ResourceFile(resourceName, file));
        if (previous != null && !previous.path().equals(file)) {
            throw new IllegalArgumentException("Duplicate application resource '" + resourceName + "': "
                    + previous.path() + " and " + file);
        }
    }

    private static List<ApplicationConfig> applicationConfigs(List<ResourceFile> resourceFiles) throws IOException {
        List<ApplicationConfig> configs = new ArrayList<>();
        for (ResourceFile resourceFile : resourceFiles) {
            if (isRootApplicationYaml(resourceFile.name())) {
                configs.add(ApplicationConfig.read(resourceFile.path(), resourceFile.name()));
            }
        }
        return List.copyOf(configs);
    }

    private static List<ResourceFile> classpathResourceFiles(List<ResourceFile> resourceFiles) {
        return resourceFiles.stream()
                .filter(resourceFile -> !isRootApplicationYaml(resourceFile.name()))
                .toList();
    }

    private static boolean isRootApplicationYaml(String resourceName) {
        return APPLICATION_CONFIG_FILES.contains(resourceName);
    }

    private static List<Path> dependencyClasspath(LaunchOptions options, List<ApplicationConfig> applicationConfigs) throws IOException {
        List<DependencyManifest> manifests = dependencyManifests(options, applicationConfigs);
        if (manifests.isEmpty()) {
            return List.of();
        }

        DependencyManifest manifest = DependencyManifest.merge(manifests);
        List<DependencyCoordinate> dependencies = manifest.dependencies(options.test());
        if (dependencies.isEmpty()) {
            return List.of();
        }

        return resolveDependencies(manifest, dependencies);
    }

    private static List<DependencyManifest> dependencyManifests(LaunchOptions options, List<ApplicationConfig> applicationConfigs) throws IOException {
        if (options.disableDependencies()) {
            return List.of();
        }
        List<DependencyManifest> manifests = new ArrayList<>();
        for (ApplicationConfig applicationConfig : applicationConfigs) {
            manifests.add(DependencyManifest.fromProperties(applicationConfig.source(), applicationConfig.properties()));
        }
        if (options.depsFile().isPresent()) {
            Path configured = options.depsFile().get().toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                throw new IllegalArgumentException("Dependency file does not exist: " + configured);
            }
            ApplicationConfig dependencyConfig = ApplicationConfig.read(configured, configured.getFileName().toString());
            manifests.add(DependencyManifest.fromProperties(dependencyConfig.source(), dependencyConfig.properties()));
        }
        return List.copyOf(manifests);
    }

    private static List<Path> resolveDependencies(
            DependencyManifest manifest,
            List<DependencyCoordinate> dependencies
    ) {
        failOnDirectVersionConflicts(manifest.source(), dependencies);

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

    private static void failOnDirectVersionConflicts(String source, List<DependencyCoordinate> dependencies) {
        Map<String, String> versions = new HashMap<>();
        for (DependencyCoordinate dependency : dependencies) {
            String key = dependency.groupId() + ":" + dependency.artifactId();
            String previous = versions.putIfAbsent(key, dependency.version());
            if (previous != null && !previous.equals(dependency.version())) {
                throw new IllegalArgumentException(source + ": Conflicting direct dependency versions for "
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

    private static List<JavaFileObject> testSupportSources() {
        return List.of(sourceFile("io.crema.micronaut.test.SourceLauncherContextBuilder", """
                package io.crema.micronaut.test;

                import io.micronaut.context.ApplicationContext;
                import io.micronaut.context.DefaultApplicationContextBuilder;
                import io.micronaut.context.env.PropertySource;
                import java.lang.reflect.InvocationTargetException;
                import java.util.Map;

                public final class SourceLauncherContextBuilder extends DefaultApplicationContextBuilder {
                    public SourceLauncherContextBuilder() {
                        classLoader(applicationClassLoader());
                        propertySources(testPropertySources());
                        properties(testProperties());
                    }

                    @Override
                    public ApplicationContext build() {
                        ApplicationContext context = super.build();
                        addGeneratedBeanDefinitionReferences(context, applicationClassLoader());
                        return context;
                    }

                    private static void addGeneratedBeanDefinitionReferences(
                            ApplicationContext context,
                            ClassLoader classLoader
                    ) {
                        try {
                            Class<?> bridge = Class.forName(
                                    "io.micronaut.core.io.service.DynamicServiceLoaderBridge",
                                    true,
                                    SourceLauncherContextBuilder.class.getClassLoader()
                            );
                            bridge.getMethod(
                                    "addGeneratedBeanDefinitionReferences",
                                    ApplicationContext.class,
                                    ClassLoader.class
                            ).invoke(null, context, classLoader);
                        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                            throw new IllegalStateException("Cannot access source launcher service loader bridge.", e);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException runtimeException) {
                                throw runtimeException;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new IllegalStateException("Source launcher service loader bridge failed.", cause);
                        }
                    }

                    private static ClassLoader applicationClassLoader() {
                        return Thread.currentThread().getContextClassLoader();
                    }

                    private static PropertySource[] testPropertySources() {
                        try {
                            Class<?> bridge = Class.forName(
                                    "io.micronaut.core.io.service.DynamicServiceLoaderBridge",
                                    true,
                                    SourceLauncherContextBuilder.class.getClassLoader()
                            );
                            return (PropertySource[]) bridge.getMethod("sourceLauncherTestPropertySources").invoke(null);
                        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                            throw new IllegalStateException("Cannot access source launcher test property sources.", e);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException runtimeException) {
                                throw runtimeException;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new IllegalStateException("Cannot read source launcher test property sources.", cause);
                        }
                    }

                    @SuppressWarnings("unchecked")
                    private static Map<String, Object> testProperties() {
                        try {
                            Class<?> bridge = Class.forName(
                                    "io.micronaut.core.io.service.DynamicServiceLoaderBridge",
                                    true,
                                    SourceLauncherContextBuilder.class.getClassLoader()
                            );
                            return (Map<String, Object>) bridge.getMethod("sourceLauncherTestProperties").invoke(null);
                        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                            throw new IllegalStateException("Cannot access source launcher test properties.", e);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException runtimeException) {
                                throw runtimeException;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new IllegalStateException("Cannot read source launcher test properties.", cause);
                        }
                    }
                }
                """));
    }

    private static JavaFileObject sourceFile(String binaryName, String source) {
        return new InMemorySourceFile(binaryName, source);
    }

    private static InMemoryCompilationOutput compileApplicationAndTests(
            List<Path> sourceFiles,
            List<Path> testSourceFiles,
            List<JavaFileObject> testSupportSourceFiles,
            InMemoryClassPath launcherClasspath,
            List<Path> dependencyClasspath,
            List<String> annotationPatterns,
            List<String> testAnnotationPatterns,
            List<ResourceFile> resourceFiles
    ) throws IOException {
        InMemoryCompilationOutput output = new InMemoryCompilationOutput();
        for (ResourceFile resourceFile : resourceFiles) {
            output.putResource(resourceFile.name(), Files.readAllBytes(resourceFile.path()));
        }
        compile(sourceFiles, List.of(), output, launcherClasspath, annotationPatterns, true, dependencyClasspath);
        if (!testSourceFiles.isEmpty()) {
            compile(testSourceFiles, testSupportSourceFiles, output, launcherClasspath, testAnnotationPatterns, true, dependencyClasspath);
            addSourceLauncherContextBuilderToMicronautTests(output);
        }
        return output.snapshot();
    }

    private record ResourceFile(String name, Path path) {
    }

    private record ApplicationConfig(String source, Map<String, Object> properties, PropertySource propertySource) {
        private static ApplicationConfig read(Path path, String name) throws IOException {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader(false);
            Map<String, Object> properties;
            try (InputStream in = Files.newInputStream(path)) {
                properties = Map.copyOf(loader.read(name, in));
            }
            return new ApplicationConfig(
                    path.toString(),
                    properties,
                    PropertySource.of(name, properties, loader.getOrder())
            );
        }
    }

    private static PropertySource[] propertySources(List<ApplicationConfig> applicationConfigs) {
        return applicationConfigs.stream()
                .map(ApplicationConfig::propertySource)
                .toArray(PropertySource[]::new);
    }

    private static void loadBuiltInJdbcDrivers(
            List<ApplicationConfig> applicationConfigs,
            List<ResourceFile> resourceFiles,
            Map<String, Object> properties
    ) throws IOException {
        if (usesBuiltInSqliteDriver(applicationConfigs, resourceFiles, properties)) {
            try {
                Class.forName(org.sqlite.JDBC.class.getName(), true, MicronautSourceLauncher.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Built-in SQLite JDBC driver is not available.", e);
            }
        }
    }

    private static boolean usesBuiltInSqliteDriver(
            List<ApplicationConfig> applicationConfigs,
            List<ResourceFile> resourceFiles,
            Map<String, Object> properties
    ) throws IOException {
        for (ApplicationConfig applicationConfig : applicationConfigs) {
            for (Map.Entry<String, Object> entry : applicationConfig.properties().entrySet()) {
                if (mentionsBuiltInSqliteDriver(entry.getKey()) ||
                        mentionsBuiltInSqliteDriver(String.valueOf(entry.getValue()))) {
                    return true;
                }
            }
        }
        for (ResourceFile resourceFile : resourceFiles) {
            String content = new String(Files.readAllBytes(resourceFile.path()), StandardCharsets.ISO_8859_1);
            if (mentionsBuiltInSqliteDriver(content)) {
                return true;
            }
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (mentionsBuiltInSqliteDriver(entry.getKey()) ||
                    mentionsBuiltInSqliteDriver(String.valueOf(entry.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsBuiltInSqliteDriver(String value) {
        return value.contains("org.sqlite.JDBC") || value.contains("jdbc:sqlite:");
    }

    private static void addSourceLauncherContextBuilderToMicronautTests(InMemoryCompilationOutput output) {
        output.transformClasses((binaryName, bytes) -> addSourceLauncherContextBuilderToMicronautTest(binaryName, bytes));
    }

    private static byte[] addSourceLauncherContextBuilderToMicronautTest(String binaryName, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                AnnotationVisitor delegate = super.visitAnnotation(descriptor, visible);
                if (!MICRONAUT_TEST_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                    private boolean hasContextBuilder;

                    @Override
                    public void visit(String name, Object value) {
                        if ("contextBuilder".equals(name)) {
                            hasContextBuilder = true;
                        }
                        super.visit(name, value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if ("contextBuilder".equals(name)) {
                            hasContextBuilder = true;
                        }
                        return super.visitArray(name);
                    }

                    @Override
                    public void visitEnd() {
                        if (!hasContextBuilder) {
                            AnnotationVisitor array = super.visitArray("contextBuilder");
                            array.visit(null, Type.getType("L" + SOURCE_LAUNCHER_CONTEXT_BUILDER.replace('.', '/') + ";"));
                            array.visitEnd();
                            changed[0] = true;
                        }
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : bytes;
    }

    private static void compile(
            List<Path> sourceFiles,
            List<JavaFileObject> additionalSources,
            InMemoryCompilationOutput output,
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
            standardFileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, additionalClassPath);
            JavaFileManager fileManager = new InMemoryClassPathFileManager(standardFileManager, launcherClasspath, output);

            List<JavaFileObject> files = new ArrayList<>(sourceFiles.size() + additionalSources.size());
            standardFileManager.getJavaFileObjectsFromPaths(sourceFiles).forEach(files::add);
            files.addAll(additionalSources);
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
            InMemoryCompilationOutput compiledOutput,
            List<Path> dependencyClasspath,
            List<ApplicationConfig> applicationConfigs,
            int port,
            Map<String, Object> launchProperties,
            StartupTimings timings
    ) throws Exception {
        long phaseStartNanos = System.nanoTime();
        MemoryApplicationClassLoader applicationClassLoader = applicationClassLoader(compiledOutput, dependencyClasspath);
        Thread.currentThread().setContextClassLoader(applicationClassLoader);
        timings.record("server classloader setup", phaseStartNanos);

        Map<String, Object> properties = launchProperties(port, launchProperties);

        ApplicationContextBuilder builder = ApplicationContext.builder()
                .classLoader(applicationClassLoader)
                .propertySources(propertySources(applicationConfigs))
                .properties(properties);
        phaseStartNanos = System.nanoTime();
        ApplicationContext context = builder.build();
        timings.record("application context build", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        DynamicServiceLoaderBridge.addGeneratedBeanDefinitionReferences(context, applicationClassLoader);
        timings.record("load generated bean references", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        context.start();
        timings.record("application context start", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
        timings.record("embedded server start", phaseStartNanos);
        return new StartedServer(server, applicationClassLoader, context);
    }

    private static MemoryApplicationClassLoader applicationClassLoader(
            InMemoryCompilationOutput compiledOutput,
            List<Path> dependencyClasspath
    ) throws IOException {
        List<URL> urls = new ArrayList<>(dependencyClasspath.size());
        for (Path dependency : dependencyClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        return new MemoryApplicationClassLoader(
                urls.toArray(URL[]::new),
                MicronautSourceLauncher.class.getClassLoader(),
                compiledOutput,
                dependencyClasspath
        );
    }

    private static Map<String, Object> launchProperties(int port, Map<String, Object> launchProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.application.name", "micronaut-source-demo");
        properties.putAll(launchProperties);
        properties.put("micronaut.server.port", port);
        return properties;
    }

    private static int runTests(
            List<Path> testSourceFiles,
            InMemoryCompilationOutput compiledOutput,
            List<Path> dependencyClasspath,
            List<ApplicationConfig> applicationConfigs,
            int port,
            Map<String, Object> launchProperties,
            StartupTimings timings
    ) throws Exception {
        long phaseStartNanos = System.nanoTime();
        MemoryApplicationClassLoader applicationClassLoader = applicationClassLoader(compiledOutput, dependencyClasspath);
        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(applicationClassLoader);
        timings.record("test classloader setup", phaseStartNanos);

        DynamicServiceLoaderBridge.configureSourceLauncherTestProperties(
                propertySources(applicationConfigs),
                launchProperties(port, launchProperties)
        );
        try {
            List<Class<?>> testClasses = new ArrayList<>();
            for (Path testSourceFile : testSourceFiles) {
                testClasses.add(Class.forName(binaryName(testSourceFile), true, applicationClassLoader));
            }

            phaseStartNanos = System.nanoTime();
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
            timings.record("junit execution", phaseStartNanos);
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
            DynamicServiceLoaderBridge.clearSourceLauncherTestProperties();
            applicationClassLoader.close();
        }
    }

    private static String binaryName(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        return binaryName(source);
    }

    private static String binaryName(String source) {
        var classMatcher = TOP_LEVEL_CLASS_DECLARATION.matcher(source);
        if (!classMatcher.find()) {
            throw new IllegalArgumentException("No top-level class found in source.");
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

    private static final class MemoryApplicationClassLoader extends URLClassLoader
            implements DynamicServiceLoaderBridge.GeneratedServiceProvider {
        private final InMemoryCompilationOutput output;
        private final List<Path> dependencyClasspath;
        private final Map<String, List<String>> dependencyServices = new HashMap<>();

        private MemoryApplicationClassLoader(
                URL[] urls,
                ClassLoader parent,
                InMemoryCompilationOutput output,
                List<Path> dependencyClasspath
        ) {
            super(urls, parent);
            this.output = output;
            this.dependencyClasspath = List.copyOf(dependencyClasspath);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = output.classBytes(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }

        @Override
        public URL getResource(String name) {
            URL resource = output.resourceUrl(name);
            return resource != null ? resource : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> result = new ArrayList<>();
            URL resource = output.resourceUrl(name);
            if (resource != null) {
                result.add(resource);
            }
            super.getResources(name).asIterator().forEachRemaining(result::add);
            return Collections.enumeration(result);
        }

        @Override
        public List<String> generatedMicronautServiceClassNames(String serviceName) {
            LinkedHashSet<String> classNames = new LinkedHashSet<>(output.generatedMicronautServiceClassNames(serviceName));
            classNames.addAll(dependencyMicronautServiceClassNames(serviceName));
            return List.copyOf(classNames);
        }

        private synchronized List<String> dependencyMicronautServiceClassNames(String serviceName) {
            return dependencyServices.computeIfAbsent(serviceName, this::readDependencyMicronautServiceClassNames);
        }

        private List<String> readDependencyMicronautServiceClassNames(String serviceName) {
            String prefix = "META-INF/micronaut/" + serviceName + "/";
            LinkedHashSet<String> classNames = new LinkedHashSet<>();
            for (Path dependency : dependencyClasspath) {
                try (JarFile jarFile = new JarFile(dependency.toFile())) {
                    jarFile.stream()
                            .map(entry -> entry.getName())
                            .filter(name -> name.startsWith(prefix))
                            .map(name -> name.substring(prefix.length()))
                            .filter(name -> !name.isEmpty() && !name.contains("/"))
                            .filter(this::canLoadClass)
                            .sorted()
                            .forEach(classNames::add);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read Micronaut service entries from " + dependency, e);
                }
            }
            return List.copyOf(classNames);
        }

        private boolean canLoadClass(String className) {
            try {
                Class.forName(className, false, this).getDeclaredConstructor().newInstance();
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }
    }

    private static final class InMemoryCompilationOutput {
        private final Map<String, byte[]> classes;
        private final Map<String, byte[]> resources;

        private InMemoryCompilationOutput() {
            this(new HashMap<>(), new HashMap<>());
        }

        private InMemoryCompilationOutput(Map<String, byte[]> classes, Map<String, byte[]> resources) {
            this.classes = classes;
            this.resources = resources;
        }

        private synchronized InMemoryCompilationOutput snapshot() {
            return new InMemoryCompilationOutput(copyBytes(classes), copyBytes(resources));
        }

        private static Map<String, byte[]> copyBytes(Map<String, byte[]> source) {
            Map<String, byte[]> copy = new HashMap<>();
            source.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            return Map.copyOf(copy);
        }

        private synchronized void putClass(String binaryName, byte[] bytes) {
            classes.put(binaryName, bytes.clone());
            resources.put(classResourceName(binaryName), bytes.clone());
        }

        private synchronized void putResource(String resourceName, byte[] bytes) {
            resources.put(resourceName, bytes.clone());
        }

        private synchronized byte[] classBytes(String binaryName) {
            byte[] bytes = classes.get(binaryName);
            return bytes == null ? null : bytes.clone();
        }

        private synchronized byte[] resourceBytes(String resourceName) {
            byte[] bytes = resources.get(resourceName);
            return bytes == null ? null : bytes.clone();
        }

        private synchronized URL resourceUrl(String resourceName) {
            byte[] bytes = resourceBytes(resourceName);
            if (bytes == null) {
                return null;
            }
            try {
                return new URL(null, "memory:///" + resourceName, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override
                            public void connect() {
                            }

                            @Override
                            public InputStream getInputStream() {
                                return new ByteArrayInputStream(bytes);
                            }
                        };
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create in-memory resource URL: " + resourceName, e);
            }
        }

        private synchronized List<MemoryOutputClassFile> list(String packageName, boolean recurse) {
            String prefix = packageName.isEmpty() ? "" : packageName + ".";
            List<MemoryOutputClassFile> result = new ArrayList<>();
            classes.forEach((binaryName, bytes) -> {
                String candidatePackage = InMemoryClassPath.packageName(binaryName);
                if (candidatePackage.equals(packageName) || (recurse && candidatePackage.startsWith(prefix))) {
                    result.add(new MemoryOutputClassFile(binaryName, bytes));
                }
            });
            return result;
        }

        private synchronized MemoryOutputClassFile getClassFile(String binaryName) {
            byte[] bytes = classes.get(binaryName);
            return bytes == null ? null : new MemoryOutputClassFile(binaryName, bytes);
        }

        private synchronized MemoryOutputResourceFile getResourceFile(String resourceName) {
            byte[] bytes = resources.get(resourceName);
            return bytes == null ? null : new MemoryOutputResourceFile(resourceName, bytes);
        }

        private synchronized List<String> generatedMicronautServiceClassNames(String serviceName) {
            String prefix = "META-INF/micronaut/" + serviceName + "/";
            return resources.keySet().stream()
                    .filter(name -> name.startsWith(prefix))
                    .map(name -> name.substring(prefix.length()))
                    .filter(name -> !name.isEmpty() && !name.contains("/"))
                    .sorted()
                    .toList();
        }

        private static String classResourceName(String binaryName) {
            return binaryName.replace('.', '/') + ".class";
        }

        private synchronized void transformClasses(ClassTransformer transformer) {
            List<String> binaryNames = new ArrayList<>(classes.keySet());
            for (String binaryName : binaryNames) {
                byte[] transformed = transformer.transform(binaryName, classes.get(binaryName).clone());
                putClass(binaryName, transformed);
            }
        }
    }

    @FunctionalInterface
    private interface ClassTransformer {
        byte[] transform(String binaryName, byte[] bytes);
    }

    private static final class InMemoryClassPath {
        private final Map<String, InMemoryClassFile> byBinaryName;
        private final Map<String, List<InMemoryClassFile>> byPackageName;

        private InMemoryClassPath(Map<String, InMemoryClassFile> byBinaryName, Map<String, List<InMemoryClassFile>> byPackageName) {
            this.byBinaryName = byBinaryName;
            this.byPackageName = byPackageName;
        }

        private static InMemoryClassPath fromLauncherResources(boolean includeTestLibraries) throws IOException {
            ClassLoader classLoader = MicronautSourceLauncher.class.getClassLoader();
            Map<String, InMemoryClassFile> byBinaryName = new HashMap<>();
            Map<String, List<InMemoryClassFile>> byPackageName = new HashMap<>();
            for (String resource : readLibIndex(classLoader)) {
                if (!isJavacClasspathLibrary(resource, includeTestLibraries)) {
                    continue;
                }
                try (InputStream in = classLoader.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("Missing launcher resource: " + resource);
                    }
                    indexJar(resource, in.readAllBytes(), byBinaryName, byPackageName);
                }
            }
            return new InMemoryClassPath(Map.copyOf(byBinaryName), copyPackageIndex(byPackageName));
        }

        private static boolean isJavacClasspathLibrary(String resource, boolean includeTestLibraries) {
            String fileName = resource.substring(resource.lastIndexOf('/') + 1);
            if (!includeTestLibraries && isTestLibrary(fileName)) {
                return false;
            }
            return !isDependencyResolverLibrary(fileName);
        }

        private static boolean isTestLibrary(String fileName) {
            return fileName.startsWith("junit-") ||
                    fileName.startsWith("apiguardian-api-") ||
                    fileName.startsWith("opentest4j-") ||
                    fileName.startsWith("micronaut-test-");
        }

        private static boolean isDependencyResolverLibrary(String fileName) {
            return fileName.startsWith("maven-") ||
                    fileName.startsWith("org.eclipse.sisu.") ||
                    fileName.startsWith("plexus-") ||
                    fileName.startsWith("commons-codec-") ||
                    fileName.startsWith("commons-logging-") ||
                    fileName.startsWith("httpclient-") ||
                    fileName.startsWith("httpcore-");
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
        private final InMemoryCompilationOutput output;

        private InMemoryClassPathFileManager(
                StandardJavaFileManager fileManager,
                InMemoryClassPath classPath,
                InMemoryCompilationOutput output
        ) {
            super(fileManager);
            this.classPath = classPath;
            this.output = output;
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, java.util.Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            Iterable<JavaFileObject> delegateFiles = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)) {
                return delegateFiles;
            }

            List<JavaFileObject> result = new ArrayList<>();
            result.addAll(output.list(packageName, recurse));
            delegateFiles.forEach(result::add);
            result.addAll(classPath.list(packageName, recurse));
            return result;
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
            if (location == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS) {
                MemoryOutputClassFile outputFile = output.getClassFile(className);
                if (outputFile != null) {
                    return outputFile;
                }
                InMemoryClassFile file = classPath.get(className);
                if (file != null) {
                    return file;
                }
            }
            return super.getJavaFileForInput(location, className, kind);
        }

        @Override
        public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
            if (location == StandardLocation.CLASS_PATH) {
                String resourceName = resourceName(packageName, relativeName);
                if (relativeName.endsWith(".class")) {
                    String simpleName = relativeName.substring(0, relativeName.length() - ".class".length()).replace('/', '.');
                    String binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                    MemoryOutputClassFile outputFile = output.getClassFile(binaryName);
                    if (outputFile != null) {
                        return outputFile;
                    }
                    InMemoryClassFile file = classPath.get(binaryName);
                    if (file != null) {
                        return file;
                    }
                }

                MemoryOutputResourceFile resource = output.getResourceFile(resourceName);
                if (resource != null) {
                    return resource;
                }
            }
            return super.getFileForInput(location, packageName, relativeName);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location,
                String className,
                JavaFileObject.Kind kind,
                FileObject sibling
        ) throws IOException {
            if (location == StandardLocation.CLASS_OUTPUT && kind == JavaFileObject.Kind.CLASS) {
                return new WritableMemoryClassFile(className, output);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        @Override
        public JavaFileObject getJavaFileForOutputForOriginatingFiles(
                Location location,
                String className,
                JavaFileObject.Kind kind,
                FileObject... originatingFiles
        ) throws IOException {
            return getJavaFileForOutput(
                    location,
                    className,
                    kind,
                    originatingFiles.length == 0 ? null : originatingFiles[0]
            );
        }

        @Override
        public FileObject getFileForOutput(
                Location location,
                String packageName,
                String relativeName,
                FileObject sibling
        ) throws IOException {
            if (location == StandardLocation.CLASS_OUTPUT) {
                return new WritableMemoryResourceFile(resourceName(packageName, relativeName), output);
            }
            return super.getFileForOutput(location, packageName, relativeName, sibling);
        }

        @Override
        public FileObject getFileForOutputForOriginatingFiles(
                Location location,
                String packageName,
                String relativeName,
                FileObject... originatingFiles
        ) throws IOException {
            return getFileForOutput(
                    location,
                    packageName,
                    relativeName,
                    originatingFiles.length == 0 ? null : originatingFiles[0]
            );
        }

        @Override
        public boolean hasLocation(Location location) {
            return location == StandardLocation.CLASS_OUTPUT || super.hasLocation(location);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InMemoryClassFile inMemoryClassFile) {
                return inMemoryClassFile.binaryName();
            }
            if (file instanceof MemoryOutputClassFile memoryOutputClassFile) {
                return memoryOutputClassFile.binaryName();
            }
            return super.inferBinaryName(location, file);
        }

        private static String resourceName(String packageName, String relativeName) {
            if (packageName == null || packageName.isEmpty()) {
                return relativeName;
            }
            return packageName.replace('.', '/') + "/" + relativeName;
        }
    }

    private static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final String source;

        private InMemorySourceFile(String binaryName, String source) {
            super(URI.create("mem:///source/" + binaryName.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE);
            this.binaryName = binaryName;
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }

        @Override
        public String getName() {
            return binaryName;
        }
    }

    private static final class WritableMemoryClassFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final InMemoryCompilationOutput output;
        private byte[] bytes;

        private WritableMemoryClassFile(String binaryName, InMemoryCompilationOutput output) {
            super(URI.create("mem:///classes/" + binaryName.replace('.', '/') + ".class"), JavaFileObject.Kind.CLASS);
            this.binaryName = binaryName;
            this.output = output;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    bytes = toByteArray();
                    output.putClass(binaryName, bytes);
                }
            };
        }

        @Override
        public InputStream openInputStream() {
            if (bytes == null) {
                throw new IllegalStateException("Class has not been written yet: " + binaryName);
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class WritableMemoryResourceFile extends SimpleJavaFileObject {
        private final String resourceName;
        private final InMemoryCompilationOutput output;
        private byte[] bytes;

        private WritableMemoryResourceFile(String resourceName, InMemoryCompilationOutput output) {
            super(URI.create("mem:///resources/" + resourceName), JavaFileObject.Kind.OTHER);
            this.resourceName = resourceName;
            this.output = output;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    bytes = toByteArray();
                    output.putResource(resourceName, bytes);
                }
            };
        }

        @Override
        public InputStream openInputStream() {
            if (bytes == null) {
                throw new IllegalStateException("Resource has not been written yet: " + resourceName);
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class MemoryOutputClassFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final byte[] bytes;

        private MemoryOutputClassFile(String binaryName, byte[] bytes) {
            super(URI.create("mem:///classes/" + binaryName.replace('.', '/') + ".class"), JavaFileObject.Kind.CLASS);
            this.binaryName = binaryName;
            this.bytes = bytes.clone();
        }

        private String binaryName() {
            return binaryName;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class MemoryOutputResourceFile extends SimpleJavaFileObject {
        private final String resourceName;
        private final byte[] bytes;

        private MemoryOutputResourceFile(String resourceName, byte[] bytes) {
            super(URI.create("mem:///resources/" + resourceName), JavaFileObject.Kind.OTHER);
            this.resourceName = resourceName;
            this.bytes = bytes.clone();
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public String getName() {
            return resourceName;
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
            boolean disableDependencies
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
            boolean disableDependencies = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--" -> parsingTestSources = true;
                    case "--test" -> test = true;
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--timings" -> timings = true;
                    case "--deps-file" -> depsFile = Optional.of(Path.of(requireValue(args, ++i, arg)));
                    case "--no-dependencies", "--no-deps-file" -> disableDependencies = true;
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
                    disableDependencies
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static void usageAndExit() {
            System.err.println("Usage: ./micronaut [--port 8080] [--timings] [--deps-file dependencies.yml] [--no-dependencies] [--package demo] [--property key=value] <source.java|source-directory>...");
            System.err.println("       ./micronaut --test [--port 8080] [--deps-file dependencies.yml] <app-source.java|app-source-directory>... -- <test-source.java|test-source-directory>...");
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
            String source,
            List<String> dependencies,
            List<String> testDependencies,
            List<String> repositories
    ) {
        private static DependencyManifest fromProperties(String source, Map<String, Object> properties) {
            List<String> dependencies = dependencyValues(source, "dependencies.main", properties.get("dependencies.main"));
            if (dependencies.isEmpty()) {
                dependencies = dependencyValues(source, "dependencies", properties.get("dependencies"));
            }
            List<String> testDependencies = dependencyValues(source, "dependencies.test", properties.get("dependencies.test"));
            if (testDependencies.isEmpty()) {
                testDependencies = dependencyValues(source, "testDependencies", properties.get("testDependencies"));
            }
            List<String> repositories = dependencyValues(source, "dependencies.repositories", properties.get("dependencies.repositories"));
            if (repositories.isEmpty()) {
                repositories = dependencyValues(source, "repositories", properties.get("repositories"));
            }
            return new DependencyManifest(
                    source,
                    List.copyOf(dependencies),
                    List.copyOf(testDependencies),
                    List.copyOf(repositories)
            );
        }

        private static DependencyManifest merge(List<DependencyManifest> manifests) {
            List<String> dependencies = new ArrayList<>();
            List<String> testDependencies = new ArrayList<>();
            List<String> repositories = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            for (DependencyManifest manifest : manifests) {
                dependencies.addAll(manifest.dependencies());
                testDependencies.addAll(manifest.testDependencies());
                repositories.addAll(manifest.repositories());
                sources.add(manifest.source());
            }
            return new DependencyManifest(
                    String.join(", ", sources),
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
                    .map(value -> DependencyCoordinate.parse(source, value))
                    .toList();
        }

        private static List<String> dependencyValues(String source, String property, Object value) {
            if (value == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    values.add(dependencyValue(source, property, item));
                }
            } else if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    values.add(dependencyValue(source, property, java.lang.reflect.Array.get(value, i)));
                }
            } else {
                values.add(dependencyValue(source, property, value));
            }
            return List.copyOf(values);
        }

        private static String dependencyValue(String source, String property, Object value) {
            if (value == null) {
                throw new IllegalArgumentException(source + ": Empty dependency value in " + property + ".");
            }
            String string = value.toString().trim();
            if (string.isEmpty()) {
                throw new IllegalArgumentException(source + ": Empty dependency value in " + property + ".");
            }
            return string;
        }
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
        private String key() {
            return groupId + ":" + artifactId;
        }

        private static DependencyCoordinate parse(String source, String value) {
            String[] parts = value.split(":");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalArgumentException(source + ": Dependency coordinates must use groupId:artifactId:version syntax: " + value);
            }
            return new DependencyCoordinate(parts[0], parts[1], parts[2]);
        }
    }
}
