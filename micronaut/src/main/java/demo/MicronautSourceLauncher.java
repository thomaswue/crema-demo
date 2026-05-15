package demo;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.io.service.DynamicServiceLoaderBridge;
import io.micronaut.runtime.server.EmbeddedServer;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class MicronautSourceLauncher {
    private static final String LIB_INDEX = "launcher-libs.index";
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );

    private MicronautSourceLauncher() {
    }

    public static void main(String[] args) throws Exception {
        configureJavaHome();

        LaunchOptions options = LaunchOptions.parse(args);
        List<Path> sourceFiles = collectSourceFiles(options.sourcePaths());
        List<String> annotationPatterns = annotationPatterns(sourceFiles, options.annotationPatterns());

        Path workDir = Files.createTempDirectory("micronaut-source-launcher-");
        Path classesDir = Files.createDirectories(workDir.resolve("classes"));
        Path libsDir = Files.createDirectories(workDir.resolve("libs"));

        long startNanos = System.nanoTime();
        List<Path> libs = extractLauncherLibs(libsDir);
        compile(sourceFiles, classesDir, libs, annotationPatterns);
        EmbeddedServer server = startServer(classesDir, libs, options.port());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        System.out.println("Micronaut source launcher started " + server.getURL() + " in " + elapsedMillis + " ms");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "micronaut-shutdown"));
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

    private static List<Path> extractLauncherLibs(Path libsDir) throws IOException {
        ClassLoader classLoader = MicronautSourceLauncher.class.getClassLoader();
        List<String> resources = readLibIndex(classLoader);
        List<Path> libs = new ArrayList<>(resources.size());
        for (String resource : resources) {
            String fileName = Path.of(resource).getFileName().toString();
            Path target = libsDir.resolve(fileName);
            try (InputStream in = classLoader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Missing launcher resource: " + resource);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            libs.add(target);
        }
        return libs;
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
        var matcher = PACKAGE_DECLARATION.matcher(source);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private static void compile(List<Path> sourceFiles, Path classesDir, List<Path> libs, List<String> annotationPatterns) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler is available.");
        }

        String classpath = libs.stream()
                .map(Path::toString)
                .collect(Collectors.joining(System.getProperty("path.separator")));
        List<String> compilerOptions = new ArrayList<>(List.of(
                "-d", classesDir.toString(),
                "-classpath", classpath,
                "-processorpath", classpath,
                "-parameters",
                "-proc:full",
                "-Amicronaut.processing.group=demo",
                "-Amicronaut.processing.module=micronaut-source-demo"
        ));
        if (!annotationPatterns.isEmpty()) {
            compilerOptions.add("-Amicronaut.processing.annotations=" + String.join(",", annotationPatterns));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, compilerOptions, null, files);
            if (!Boolean.TRUE.equals(task.call())) {
                throw compilationFailed(diagnostics);
            }
        }
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

    private static EmbeddedServer startServer(Path classesDir, List<Path> libs, int port) throws Exception {
        List<URL> urls = new ArrayList<>(libs.size() + 1);
        urls.add(classesDir.toUri().toURL());
        for (Path lib : libs) {
            urls.add(lib.toUri().toURL());
        }

        URLClassLoader applicationClassLoader = new URLClassLoader(urls.toArray(URL[]::new), MicronautSourceLauncher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(applicationClassLoader);

        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.application.name", "micronaut-source-demo");
        properties.put("micronaut.server.port", port);

        ApplicationContextBuilder builder = ApplicationContext.builder()
                .classLoader(applicationClassLoader)
                .properties(properties);
        ApplicationContext context = builder.build();
        DynamicServiceLoaderBridge.addGeneratedBeanDefinitionReferences(context, applicationClassLoader, classesDir);
        context.start();
        return context.getBean(EmbeddedServer.class).start();
    }

    private record LaunchOptions(List<Path> sourcePaths, int port, List<String> annotationPatterns) {
        static LaunchOptions parse(String[] args) {
            List<Path> sourcePaths = new ArrayList<>();
            int port = 8080;
            List<String> annotationPatterns = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--package" -> annotationPatterns.addAll(annotationPatterns(requireValue(args, ++i, arg)));
                    case "--help", "-h" -> usageAndExit();
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        sourcePaths.add(Path.of(arg));
                    }
                }
            }

            if (sourcePaths.isEmpty()) {
                usageAndExit();
            }
            return new LaunchOptions(List.copyOf(sourcePaths), port, List.copyOf(annotationPatterns));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static void usageAndExit() {
            System.err.println("Usage: ./micronaut [--port 8080] [--package demo] <source.java|source-directory>...");
            System.exit(2);
        }

        private static List<String> annotationPatterns(String packages) {
            return Stream.of(packages.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> value.endsWith(".*") || value.equals("*") ? value : value + ".*")
                    .toList();
        }
    }
}
