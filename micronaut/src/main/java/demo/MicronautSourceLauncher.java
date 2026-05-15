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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class MicronautSourceLauncher {
    private static final String LIB_INDEX = "launcher-libs.index";
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );

    private MicronautSourceLauncher() {
    }

    public static void main(String[] args) throws Exception {
        long processStartNanos = System.nanoTime();
        configureJavaHome();

        LaunchOptions options = LaunchOptions.parse(args);
        List<Path> sourceFiles = collectSourceFiles(options.sourcePaths());
        List<String> annotationPatterns = annotationPatterns(sourceFiles, options.annotationPatterns());

        Path workDir = Files.createTempDirectory("micronaut-source-launcher-");
        Path classesDir = Files.createDirectories(workDir.resolve("classes"));
        StartupTimings timings = new StartupTimings(options.timings());
        timings.record("setup", processStartNanos);

        long startNanos = System.nanoTime();
        long phaseStartNanos = System.nanoTime();
        LauncherClasspath launcherClasspath = launcherClasspath(workDir);
        timings.record(launcherClasspath.extracted() ? "extract launcher libs" : "index launcher libs", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        try {
            compile(sourceFiles, classesDir, launcherClasspath, annotationPatterns);
        } catch (RuntimeException | IOException e) {
            if (launcherClasspath.extracted()) {
                throw e;
            }
            timings.record("in-memory launcher libs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), System.nanoTime());
            long fallbackStartNanos = System.nanoTime();
            LauncherClasspath extractedClasspath = extractedLauncherClasspath(workDir);
            timings.record("fallback extract launcher libs", fallbackStartNanos);
            compile(sourceFiles, classesDir, extractedClasspath, annotationPatterns);
            launcherClasspath = extractedClasspath;
        }
        timings.record("javac and annotation processing", phaseStartNanos);
        phaseStartNanos = System.nanoTime();
        EmbeddedServer server = startServer(classesDir, options.port(), options.properties(), timings);
        timings.record("server setup total", phaseStartNanos);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        timings.print();
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

    private static LauncherClasspath launcherClasspath(Path workDir) throws IOException {
        try {
            return new LauncherClasspath(List.of(), false, InMemoryClassPath.fromLauncherResources());
        } catch (IOException | RuntimeException e) {
            return extractedLauncherClasspath(workDir);
        }
    }

    private static LauncherClasspath extractedLauncherClasspath(Path workDir) throws IOException {
        Path libsDir = Files.createDirectories(workDir.resolve("libs"));
        return new LauncherClasspath(extractLauncherLibs(libsDir), true, null);
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

    private static void compile(List<Path> sourceFiles, Path classesDir, LauncherClasspath launcherClasspath, List<String> annotationPatterns) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler is available.");
        }

        List<String> compilerOptions = new ArrayList<>(List.of(
                "-d", classesDir.toString(),
                "-parameters",
                "-proc:full",
                "-Amicronaut.processing.group=demo",
                "-Amicronaut.processing.module=micronaut-source-demo"
        ));
        if (!annotationPatterns.isEmpty()) {
            compilerOptions.add("-Amicronaut.processing.annotations=" + String.join(",", annotationPatterns));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            standardFileManager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
            JavaFileManager fileManager = standardFileManager;
            if (launcherClasspath.inMemoryClassPath() == null) {
                standardFileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, launcherClasspath.paths());
            } else {
                fileManager = new InMemoryClassPathFileManager(standardFileManager, launcherClasspath.inMemoryClassPath());
            }

            Iterable<? extends JavaFileObject> files = standardFileManager.getJavaFileObjectsFromPaths(sourceFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, compilerOptions, null, files);
            task.setProcessors(micronautProcessors());
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

    private static EmbeddedServer startServer(
            Path classesDir,
            int port,
            Map<String, Object> launchProperties,
            StartupTimings timings
    ) throws Exception {
        long phaseStartNanos = System.nanoTime();
        List<URL> urls = new ArrayList<>(1);
        urls.add(classesDir.toUri().toURL());

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
        return server;
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

    private record LauncherClasspath(List<Path> paths, boolean extracted, InMemoryClassPath inMemoryClassPath) {
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
            int port,
            List<String> annotationPatterns,
            Map<String, Object> properties,
            boolean timings
    ) {
        static LaunchOptions parse(String[] args) {
            List<Path> sourcePaths = new ArrayList<>();
            int port = 8080;
            List<String> annotationPatterns = new ArrayList<>();
            Map<String, Object> properties = new HashMap<>();
            boolean timings = Boolean.getBoolean("crema.timings");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--timings" -> timings = true;
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
                        sourcePaths.add(Path.of(arg));
                    }
                }
            }

            if (sourcePaths.isEmpty()) {
                usageAndExit();
            }
            return new LaunchOptions(
                    List.copyOf(sourcePaths),
                    port,
                    List.copyOf(annotationPatterns),
                    Map.copyOf(properties),
                    timings
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static void usageAndExit() {
            System.err.println("Usage: ./micronaut [--port 8080] [--timings] [--package demo] [--property key=value] <source.java|source-directory>...");
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
}
