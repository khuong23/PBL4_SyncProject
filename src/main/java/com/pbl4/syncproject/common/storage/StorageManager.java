package com.pbl4.syncproject.common.storage;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;

public final class StorageManager {

    private static volatile StorageManager INSTANCE;

    public static StorageManager getInstance() {
        StorageManager r = INSTANCE;
        if (r == null) {
            synchronized (StorageManager.class) {
                r = INSTANCE;
                if (r == null) INSTANCE = r = new StorageManager();
            }
        }
        return r;
    }

    private Path rootDir;

    private StorageManager() {
        Config cfg = loadConfig();
        if (cfg.storageRoot == null || cfg.storageRoot.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu 'storage.root' trong classpath app.properties (src/main/resources/app.properties)"
            );
        }
        this.rootDir = Paths.get(cfg.storageRoot).toAbsolutePath().normalize();
    }

    // ---------- Public API ----------

    public synchronized Path getRoot() throws IOException {
        Files.createDirectories(rootDir);
        return rootDir;
    }

    /** Tạo thư mục con dưới root: ensureDirectoryUnderRoot("shared","images") */
    public Path ensureDirectoryUnderRoot(String... rel) throws IOException {
        Path p = getRoot();
        for (String n : rel) p = p.resolve(sanitizeName(n));
        p = p.normalize();
        assertWithinRoot(p);
        Files.createDirectories(p);
        return p;
    }

    /** Trả về file path an toàn dưới root (chưa ghi) */
    public Path resolveFileUnderRoot(String folderRelative, String fileName) throws IOException {
        String[] parts = folderRelative.split("[/\\\\]+"); // tách theo / hoặc \
        Path folder = ensureDirectoryUnderRoot(parts);
        Path file = folder.resolve(sanitizeName(fileName)).normalize();
        assertWithinRoot(file.getParent());
        return file;
    }

    /** Chặn path thoát khỏi root */
    public void assertWithinRoot(Path p) throws IOException {
        Path root = getRoot().toRealPath();              // root chắc chắn tồn tại
        Path normalized = p.toAbsolutePath().normalize();// KHÔNG toRealPath() trên p
        if (!normalized.startsWith(root)) {
            throw new SecurityException("Path outside of storage root: " + p);
        }
    }

    /** Sanitize tên */
    public static String sanitizeName(String name) {
        if (name == null) return "_";
        String n = name.strip();
        if (n.startsWith("📁")) n = n.substring(1).strip();
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
        if (n.equals(".") || n.equals("..") || n.isBlank()) n = "_";
        if (n.length() > 180) n = n.substring(0, 180);
        return n;
    }

    // ---------- Hỗ trợ SERVER: dựng path theo cây DB ----------

    /**
     * Dựng đường dẫn thật của một FolderID bằng Folders(FolderName, ParentFolderID)
     */
    public Path resolveFolderPathFromDb(Connection conn, int folderId) throws SQLException, IOException {
        String name = null; Integer parent = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT FolderName, ParentFolderID FROM Folders WHERE FolderID=?")) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString(1);
                    parent = (Integer) rs.getObject(2);
                }
            }
        }
        if (name == null) throw new SQLException("Folder không tồn tại: id=" + folderId);

        // Nếu là node ROOT (parent null) -> trả về đúng storage.root
        Path base = getRoot();
        if (parent == null) {
            assertWithinRoot(base);
            return base; // <storage.root>
        }

        // Không phải root: build chuỗi tên, NHƯNG bỏ qua tên của node root (parent=null)
        Path rel = Paths.get(sanitizeName(name));
        Integer cur = parent;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT FolderName, ParentFolderID FROM Folders WHERE FolderID=?")) {
            while (cur != null) {
                ps.setInt(1, cur);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) break;
                    String nm = sanitizeName(rs.getString(1));
                    Integer nextParent = (Integer) rs.getObject(2);

                    if (nextParent == null) {
                        // cur là node root -> KHÔNG thêm tên của root
                        break;
                    }
                    rel = Paths.get(nm).resolve(rel);
                    cur = nextParent;
                }
            }
        }

        Path full = base.resolve(rel).normalize();
        assertWithinRoot(full);
        return full;
    }


    // ---------- Config loader ----------

    private static final class Config {
        String storageRoot;
    }

    /** CHỈ nạp từ classpath: /app.properties */
    private Config loadConfig() {
        Properties p = new Properties();
        try (InputStream in = StorageManager.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                p.load(in);
            } else {
                // không tìm thấy file trong classpath
                // để storageRoot = null và constructor sẽ quăng IllegalStateException
            }
        } catch (IOException ignore) {}

        Config cfg = new Config();
        cfg.storageRoot = p.getProperty("storage.root");
        return cfg;
    }
}
