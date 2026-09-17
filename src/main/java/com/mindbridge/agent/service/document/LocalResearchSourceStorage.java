package com.mindbridge.agent.service.document;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
/**
 * 本地目录存储。storageKey 只使用所有者、项目和生成的不透明名，避免路径穿越。
 */
public class LocalResearchSourceStorage implements ResearchSourceStorage {

    private static final String SHA_256 = "SHA-256";

    private final Path root;

    public LocalResearchSourceStorage(MindBridgeProperties properties) {
        this.root = Path.of(properties.getResearch().getSourceStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to create research source storage directory", exception);
        }
    }

    @Override
    public StoredResearchSource store(Long ownerId, Long projectId, String filename, byte[] content) {
        String storageKey = ownerId + "/" + projectId + "/" + UUID.randomUUID();
        Path target = resolveExisting(storageKey, false);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to store research source", exception);
        }
        return new StoredResearchSource(storageKey, sha256(content), content.length);
    }

    @Override
    public byte[] load(String storageKey) {
        Path target = resolveExisting(storageKey, true);
        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new IllegalArgumentException(FILE_NOT_FOUND_MESSAGE);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveExisting(storageKey, false);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to delete research source", exception);
        }
    }

    private Path resolveExisting(String storageKey, boolean mustExist) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("..")) {
            throw new IllegalArgumentException(FILE_NOT_FOUND_MESSAGE);
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(FILE_NOT_FOUND_MESSAGE);
        }
        if (mustExist && !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(FILE_NOT_FOUND_MESSAGE);
        }
        return resolved;
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
