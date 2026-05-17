package demo.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LauncherLibIndexGenerator {
    private static final String LAUNCHER_LIBS = "launcher-libs";
    private static final String LIB_INDEX = "launcher-libs.index";
    private static final String LIB_MANIFEST = "launcher-libs.manifest";
    private static final String LIB_CLASS_INDEX = "launcher-libs.classes";

    private LauncherLibIndexGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: LauncherLibIndexGenerator <classes-directory>");
        }

        Path classesDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path launcherLibsDirectory = classesDirectory.resolve(LAUNCHER_LIBS);
        if (!Files.isDirectory(launcherLibsDirectory)) {
            throw new IOException("Missing launcher libs directory: " + launcherLibsDirectory);
        }

        List<LauncherLibrary> libraries;
        try (var files = Files.list(launcherLibsDirectory)) {
            libraries = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new LauncherLibrary(
                            LAUNCHER_LIBS + "/" + path.getFileName(),
                            role(path.getFileName().toString()),
                            path
                    ))
                    .toList();
        }

        writeLibraryIndex(classesDirectory, libraries);
        writeManifest(classesDirectory, libraries);
        writeClassIndex(classesDirectory, libraries);
    }

    private static void writeLibraryIndex(Path classesDirectory, List<LauncherLibrary> libraries) throws IOException {
        StringBuilder out = new StringBuilder();
        for (LauncherLibrary library : libraries) {
            out.append(library.resource()).append('\n');
        }
        Files.writeString(classesDirectory.resolve(LIB_INDEX), out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeManifest(Path classesDirectory, List<LauncherLibrary> libraries) throws IOException {
        StringBuilder out = new StringBuilder();
        for (LauncherLibrary library : libraries) {
            out.append("runtime").append('\t').append(library.resource()).append('\n');
            out.append(library.role()).append('\t').append(library.resource()).append('\n');
        }
        Files.writeString(classesDirectory.resolve(LIB_MANIFEST), out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeClassIndex(Path classesDirectory, List<LauncherLibrary> libraries) throws IOException {
        StringBuilder out = new StringBuilder();
        for (LauncherLibrary library : libraries) {
            if (!library.role().startsWith("compiler-")) {
                continue;
            }
            byte[] jarBytes = Files.readAllBytes(library.path());
            for (ClassEntry entry : classEntries(library.resource(), jarBytes)) {
                out.append(library.resource())
                        .append('\t').append(entry.binaryName())
                        .append('\t').append(entry.entryName())
                        .append('\t').append(entry.flags())
                        .append('\t').append(entry.method())
                        .append('\t').append(entry.dataOffset())
                        .append('\t').append(entry.compressedSize())
                        .append('\t').append(entry.uncompressedSize())
                        .append('\n');
            }
        }
        Files.writeString(classesDirectory.resolve(LIB_CLASS_INDEX), out.toString(), StandardCharsets.UTF_8);
    }

    private static List<ClassEntry> classEntries(String resource, byte[] jarBytes) throws IOException {
        List<ClassEntry> entries = new ArrayList<>();
        int eocdOffset = findEndOfCentralDirectory(jarBytes);
        int entryCount = getUnsignedShort(jarBytes, eocdOffset + 10);
        int centralDirectoryOffset = getUnsignedInt(jarBytes, eocdOffset + 16);
        int offset = centralDirectoryOffset;

        for (int i = 0; i < entryCount; i++) {
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

            if (isClassEntry(entryName)) {
                entries.add(new ClassEntry(
                        entryName,
                        binaryName(entryName),
                        flags,
                        method,
                        localDataOffset(jarBytes, localHeaderOffset),
                        compressedSize,
                        uncompressedSize
                ));
            }

            offset += 46 + nameLength + extraLength + commentLength;
        }
        return List.copyOf(entries);
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

    private static String role(String fileName) {
        if (isTestLibrary(fileName)) {
            return "compiler-test";
        }
        if (isResolverLibrary(fileName)) {
            return "resolver";
        }
        return "compiler-main";
    }

    private static boolean isTestLibrary(String fileName) {
        return fileName.startsWith("junit-") ||
                fileName.startsWith("apiguardian-api-") ||
                fileName.startsWith("opentest4j-") ||
                fileName.startsWith("micronaut-test-");
    }

    private static boolean isResolverLibrary(String fileName) {
        return fileName.startsWith("maven-") ||
                fileName.startsWith("org.eclipse.sisu.") ||
                fileName.startsWith("plexus-") ||
                fileName.startsWith("commons-codec-") ||
                fileName.startsWith("commons-logging-") ||
                fileName.startsWith("httpclient-") ||
                fileName.startsWith("httpcore-");
    }

    private record LauncherLibrary(String resource, String role, Path path) {
    }

    private record ClassEntry(
            String entryName,
            String binaryName,
            int flags,
            int method,
            int dataOffset,
            int compressedSize,
            int uncompressedSize
    ) {
    }
}
