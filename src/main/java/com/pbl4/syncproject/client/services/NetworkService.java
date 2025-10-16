package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * NetworkService (simple persistent):
 * - Giữ 1 socket xuyên suốt vòng đời client.
 * - Giao thức: JSON kết thúc bằng '\n' (tương thích server).
 * - Khi gặp IOException: đóng, kết nối lại và thử gửi lại 1 lần.
 * - Thread-safe theo mức cơ bản (synchronized khi ghi/đọc).
 */
public class NetworkService {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;

    private final String serverIP;
    private final int serverPort;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    // Khóa để gửi/nhận theo thứ tự, dùng an toàn từ nhiều luồng
    private final Object ioLock = new Object();

    public NetworkService(String serverIP, int serverPort) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
    }

    /** Gọi sớm khi app khởi động (vd: sau khi chọn IP/Port) */
    public void start() throws IOException {
        ensureConnected();
    }

    /** Đóng kết nối khi thoát ứng dụng */
    public void stop() {
        closeQuiet();
    }

    public String getServerInfo() {
        return serverIP + ":" + serverPort;
    }

    /** API dùng như cũ: gửi Request và nhận Response trên cùng 1 socket */
    public Response sendRequest(Request request) throws Exception {
        Exception last = null;

        // Thử 2 lần tối đa: lần đầu → nếu IOException thì reconnect và thử lại 1 lần
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureConnected();
                String payload = JsonUtils.toJson(request);
                if (payload == null || payload.isBlank()) {
                    throw new IllegalArgumentException("Request JSON rỗng");
                }

                String respLine;
                synchronized (ioLock) {
                    writer.write(payload);
                    writer.write('\n');      // newline-terminated
                    writer.flush();

                    socket.setSoTimeout(READ_TIMEOUT_MS);
                    respLine = reader.readLine();
                }

                if (respLine == null) throw new EOFException("Server đóng kết nối");
                Response resp = JsonUtils.fromJson(respLine, Response.class);
                if (resp == null) throw new IOException("Phản hồi không hợp lệ từ server");
                return resp;

            } catch (IOException io) {
                last = io;
                reconnect(); // đóng và kết nối lại, rồi vòng lặp sẽ thử lần 2
            }
        }
        throw (last != null ? last : new IOException("sendRequest failed"));
    }

    // ================== Nội bộ ==================

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        connect();
    }

    private void connect() throws IOException {
        closeQuiet();

        Socket s = new Socket();
        s.connect(new InetSocketAddress(serverIP, serverPort), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);

        this.socket = s;
        this.reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    private void reconnect() {
        try { connect(); } catch (IOException ignore) { /* sẽ ném lỗi ở lần gửi sau */ }
    }

    private void closeQuiet() {
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        reader = null; writer = null; socket = null;
    }
    public Response uploadFile(File file) throws Exception {
        return uploadFile(file, 1); // 1 = root folder (not 0)
    }
    public Response uploadFile(File file, int folderId) throws Exception {

        // Validate file size
        if (file.length() > 50*1024*1024) {
            throw new Exception("File quá lớn. Kích thước tối đa: 50MB" );
        }

        // Validate file exists and is readable
        if (!file.exists()) {
            throw new Exception("File không tồn tại: " + file.getAbsolutePath());
        }

        if (!file.canRead()) {
            throw new Exception("Không thể đọc file: " + file.getAbsolutePath());
        }

        try {
            // Đọc file và encode base64
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String base64Content = Base64.getEncoder().encodeToString(fileBytes);

            // Validate base64 content
            if (base64Content.isEmpty()) {
                throw new Exception("Nội dung file rỗng hoặc không thể đọc được");
            }

            // Tạo request JSON
            JsonObject data = new JsonObject();
            data.addProperty("fileName", file.getName());
            data.addProperty("fileContent", base64Content);
            data.addProperty("folderId", folderId);
            data.addProperty("fileSize", file.length());
            data.addProperty("lastModified", file.lastModified());

            Request request = new Request("UPLOAD_FILE", data);

            // Gửi request và nhận response
            return sendRequest(request);

        } catch (OutOfMemoryError e) {
            throw new Exception("File quá lớn để xử lý trong bộ nhớ. Hãy thử file nhỏ hơn.");
        } catch (IOException e) {
            throw new Exception("Lỗi đọc file: " + e.getMessage());
        } catch (Exception e) {
            throw e;
        }
    }
}
