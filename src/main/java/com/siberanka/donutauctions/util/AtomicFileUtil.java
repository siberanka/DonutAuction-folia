package com.siberanka.donutauctions.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class AtomicFileUtil {

    private AtomicFileUtil() {
    }

    public static void saveYamlAtomically(Path target, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        String body = yaml.saveToString();

        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }

        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void rotateBackups(Path source, Path backupDir, int keep) {
        try {
            Files.createDirectories(backupDir);
            if (Files.exists(source)) {
                Path backup = backupDir.resolve(source.getFileName() + "." + System.currentTimeMillis() + ".bak");
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            try (Stream<Path> stream = Files.list(backupDir)) {
                stream.filter(path -> path.getFileName().toString().startsWith(source.getFileName().toString() + "."))
                        .sorted(Comparator.comparing(Path::toString).reversed())
                        .skip(Math.max(keep, 1))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
    }

    public static Optional<Path> restoreLatestBackup(Path source, Path backupDir) {
        try {
            if (!Files.isDirectory(backupDir)) {
                return Optional.empty();
            }
            String prefix = source.getFileName().toString() + ".";
            try (Stream<Path> stream = Files.list(backupDir)) {
                Optional<Path> latest = stream
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .max(Comparator.comparing(Path::toString));
                if (latest.isEmpty()) {
                    return Optional.empty();
                }
                Files.copy(latest.get(), source, StandardCopyOption.REPLACE_EXISTING);
                return latest;
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
