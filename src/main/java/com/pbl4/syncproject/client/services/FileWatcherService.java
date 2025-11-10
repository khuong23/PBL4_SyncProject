package com.pbl4.syncproject.client.services;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * FileWatcherService:
 * - Theo dõi đệ quy thư mục với WatchService
 * - Hỗ trợ pause/resume/runMuted để tránh vòng lặp khi ghi file từ server
 * - Bộ lọc ignore mạnh: glob + prefix + suffix + ẩn file/dir
 * - Overflow tolerant: phát onOverflow + soft-rescan nhẹ để bù sự kiện rơi
 */
public class FileWatcherService {

    private WatchService watchService;
    private final Map<WatchKey, Path> keyToPathMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FileWatcher-Thread");
        t.setDaemon(true);
        return t;
    });
    private final Set<FileChangeListener> listeners = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    // Ignore matchers
    private final Set<PathMatcher> ignoreMatchers = ConcurrentHashMap.newKeySet();
    private final Set<String> ignorePrefixes = ConcurrentHashMap.newKeySet();
    private final Set<String> ignoreSuffixes = ConcurrentHashMap.newKeySet();
    private volatile boolean ignoreHidden = true;
    private volatile boolean softRescanOnOverflow = true;

    private Thread shutdownHook;

    /** Listener callback */
    public interface FileChangeListener {
        void onFileCreated(Path filePath);
        void onFileModified(Path filePath);
        void onFileDeleted(Path filePath);

        /** Mặc định: bỏ qua; caller có thể override để rescan nhẹ */
        default void onOverflow(Path watchedDirectory) {}
    }

    // ---------------- Public API ----------------

    /** Bắt đầu watcher */
    public void start() throws IOException {
        if (isRunning.get()) return;
        watchService = FileSystems.getDefault().newWatchService();
        isRunning.set(true);

        // Mặc định ignore một số pattern phổ biến + file tạm khi tải/ghi
        addDefaultIgnores();

        // Đảm bảo đóng watcher khi JVM thoát
        installShutdownHook();

        executor.submit(this::watchLoop);
        System.out.println("FileWatcher service started");
    }

    /** Dừng watcher */
    public void stop() {
        if (!isRunning.getAndSet(false)) return;

        try {
            if (watchService != null) watchService.close();
        } catch (IOException e) {
            System.err.println("Error closing watch service: " + e.getMessage());
        }
        executor.shutdownNow();
        keyToPathMap.clear();
        removeShutdownHook();
        System.out.println("FileWatcher service stopped");
    }

    /** Tạm dừng phát sự kiện (dùng trong mirror/down-sync hoặc rename/ghi file chủ động) */
    public void pause() {
        paused.set(true);
    }

    /** Tiếp tục phát sự kiện */
    public void resume() {
        paused.set(false);
    }

    /** Chạy 1 khối code trong trạng thái muted (tự pause/resume) */
    public void runMuted(Runnable r) {
        boolean prev = paused.getAndSet(true);
        try { r.run(); } finally { paused.set(prev); }
    }

    public boolean isRunning() { return isRunning.get(); }
    public boolean isPaused()  { return paused.get(); }

    /** Bắt đầu watch một thư mục (đệ quy) */
    public void watchDirectory(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }
        registerRecursive(directory);
        System.out.println("Started watching directory: " + directory);
    }

    /** Thêm listener */
    public void addListener(FileChangeListener listener) {
        listeners.add(listener);
    }

    /** Gỡ listener */
    public void removeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }

    /** Số lượng directories đang watch */
    public int getWatchedDirectoryCount() {
        return keyToPathMap.size();
    }

    // ---- Ignore controls ----
    public void setIgnoreHidden(boolean enabled) { this.ignoreHidden = enabled; }
    public void setSoftRescanOnOverflow(boolean enabled) { this.softRescanOnOverflow = enabled; }

    public void addIgnoreGlob(String glob) {
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        ignoreMatchers.add(m);
    }
    public void addIgnoreMatcher(PathMatcher matcher) {
        ignoreMatchers.add(matcher);
    }
    public void addIgnorePrefixes(String... prefixes) {
        if (prefixes == null) return;
        for (String p : prefixes) if (p != null && !p.isEmpty()) ignorePrefixes.add(p);
    }
    public void addIgnoreSuffixes(String... suffixes) {
        if (suffixes == null) return;
        for (String s : suffixes) if (s != null && !s.isEmpty()) ignoreSuffixes.add(s);
    }

    // --------------- Internal ----------------

    private void addDefaultIgnores() {
        // File tạm & dotfiles thường gặp
        addIgnoreGlob("**/*.tmp");
        addIgnoreGlob("**/*.swp");
        addIgnoreGlob("**/*~");
        addIgnoreGlob("**/.DS_Store");
        addIgnoreGlob("**/Thumbs.db");

        // SCM & IDE
        addIgnoreGlob("**/.git/**");
        addIgnoreGlob("**/.idea/**");

        // Build dirs phổ biến
        addIgnoreGlob("**/target/**");
        addIgnoreGlob("**/build/**");
        addIgnoreGlob("**/out/**");

        // Tải xuống/ghi tạm ở nhiều app/browser/editor
        addIgnoreSuffixes(".part", ".partial", ".crdownload", ".download", ".tmp");
        addIgnorePrefixes("~$"); // MS Office temp
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (shouldIgnoreDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerDirectory(Path directory) throws IOException {
        WatchKey key = directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        keyToPathMap.put(key, directory);
    }

    private void watchLoop() {
        while (isRunning.get()) {
            try {
                WatchKey key = watchService.take(); // blocking
                Path directory = keyToPathMap.get(key);
                if (directory == null) {
                    // key không còn hợp lệ (thư mục bị xóa / moved)
                    key.reset();
                    continue;
                }

                List<WatchEvent<?>> events = key.pollEvents();
                for (WatchEvent<?> event : events) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        // Báo overflow cho caller
                        notifyOverflow(directory);
                        // Soft-rescan nhẹ để bù event rơi (tùy chọn)
                        if (softRescanOnOverflow) {
                            softRescan(directory);
                        }
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path child = pathEvent.context();
                    Path fullPath = directory.resolve(child);

                    // Nếu tạo thư mục mới → phải đăng ký ngay (để theo dõi sâu)
                    if (kind == ENTRY_CREATE) {
                        try {
                            if (Files.isDirectory(fullPath)) {
                                if (!shouldIgnoreDirectory(fullPath)) {
                                    registerRecursive(fullPath);
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("Error registering new directory: " + e.getMessage());
                        }
                    }

                    // Phát sự kiện
                    handleFileEvent(kind, fullPath);
                }

                boolean valid = key.reset();
                if (!valid) {
                    keyToPathMap.remove(key);
                    if (keyToPathMap.isEmpty()) {
                        // Không còn gì để theo dõi → kết thúc
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException cwse) {
                // stop() đã được gọi
                break;
            } catch (Exception e) {
                System.err.println("Error in watch loop: " + e.getMessage());
            }
        }
    }

    private void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath) {
        if (!isRunning.get()) return;
        
        // KIỂM TRA PAUSED FLAG
        if (paused.get()) {
            System.out.println("🔇 File event IGNORED (watcher muted): " + kind.name() + " - " + filePath);
            return;
        }

        // Bỏ qua một số file/dir theo tên và theo glob
        if (shouldIgnore(filePath)) return;

        // Log gọn
        System.out.println("File event: " + kind.name() + " - " + filePath);

        // Gửi tới listener
        for (FileChangeListener l : listeners) {
            try {
                if (kind == ENTRY_CREATE) {
                    l.onFileCreated(filePath);
                } else if (kind == ENTRY_MODIFY) {
                    l.onFileModified(filePath);
                } else if (kind == ENTRY_DELETE) {
                    l.onFileDeleted(filePath);
                }
            } catch (Exception ex) {
                System.err.println("Error in file change listener: " + ex.getMessage());
            }
        }
    }

    private void notifyOverflow(Path directory) {
        for (FileChangeListener l : listeners) {
            try { l.onOverflow(directory); }
            catch (Exception ex) { System.err.println("Error in overflow listener: " + ex.getMessage()); }
        }
    }

    /** Rescan nhẹ: phát onFileModified cho toàn bộ file hiện có trong thư mục để SyncAgent có cơ hội bù sự kiện. */
    private void softRescan(Path directory) {
        if (paused.get()) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) continue;
                if (shouldIgnore(p)) continue;
                for (FileChangeListener l : listeners) {
                    try { l.onFileModified(p); } catch (Exception ignored) {}
                }
            }
        } catch (IOException ioe) {
            // best effort
        }
    }

    // ---- Ignore helpers ----

    private boolean shouldIgnoreDirectory(Path dir) {
        // Ẩn dir?
        if (ignoreHidden) {
            try {
                if (Files.isHidden(dir)) return true;
            } catch (IOException ignored) {}
        }
        // Glob matchers
        Path abs = dir.toAbsolutePath().normalize();
        for (PathMatcher m : ignoreMatchers) {
            if (m.matches(abs)) return true;
        }
        // Tên bắt đầu bằng "." cũng bỏ qua (dotdir)
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return name.startsWith(".");
    }

    private boolean shouldIgnore(Path path) {
        // Dir check (để giảm noise)
        if (Files.isDirectory(path)) {
            return shouldIgnoreDirectory(path);
        }

        String name = path.getFileName() != null ? path.getFileName().toString() : "";

        // Hidden file?
        if (ignoreHidden) {
            try {
                if (Files.isHidden(path)) return true;
            } catch (IOException ignored) {}
        }

        // Quick name-based filters
        for (String p : ignorePrefixes) {
            if (!p.isEmpty() && name.startsWith(p)) return true;
        }
        for (String s : ignoreSuffixes) {
            if (!s.isEmpty() && name.endsWith(s)) return true;
        }

        // Dotfiles & file tạm phổ biến
        if (name.startsWith(".") || ".DS_Store".equals(name) || "Thumbs.db".equalsIgnoreCase(name)) {
            return true;
        }

        // Glob matchers (áp trên đường dẫn tuyệt đối để dễ khớp **)
        Path abs = path.toAbsolutePath().normalize();
        for (PathMatcher m : ignoreMatchers) {
            if (m.matches(abs)) return true;
        }
        return false;
    }

    // ---- Shutdown hook ----
    private void installShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(() -> {
            try { stop(); } catch (Exception ignored) {}
        }, "FileWatcher-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) {}
            shutdownHook = null;
        }
    }
}
