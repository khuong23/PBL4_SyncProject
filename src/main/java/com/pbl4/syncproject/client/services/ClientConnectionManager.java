package com.pbl4.syncproject.client.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Quản lý duy nhất một kết nối TCP tới server trong suốt phiên làm việc.
 * - Thread-safe bằng lock nội bộ.
 * - Tự reconnect nếu mất kết nối.
 */
public final class ClientConnectionManager {

    private static final ClientConnectionManager INSTANCE = new ClientConnectionManager();

    private final Object lock = new Object();

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    private String serverIp;
    private int serverPort;

    private ClientConnectionManager() { }

    public static ClientConnectionManager getInstance() {
        return INSTANCE;
    }

    /** Lưu thông tin server và mở kết nối (nếu chưa mở). */
    public void connect(String ip, int port) throws IOException {
        synchronized (lock) {
            this.serverIp = ip;
            this.serverPort = port;

            if (isConnectedLocked()) {
                return; // đã kết nối, dùng luôn
            }

            socket = new Socket(ip, port);
            socket.setTcpNoDelay(true);

            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    true);

            System.out.println("✅ Connected to server " + ip + ":" + port);
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return isConnectedLocked();
        }
    }

    private boolean isConnectedLocked() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** Gửi một dòng JSON và nhận một dòng JSON về. */
    public String sendRequestAndGetResponse(String jsonRequest) throws IOException {
        synchronized (lock) {
            ensureConnectedLocked();

            writer.println(jsonRequest);
            String response = reader.readLine();

            if (response == null) {
                // Server đóng kết nối
                closeLocked();
                throw new IOException("Server closed the connection");
            }

            return response;
        }
    }

    private void ensureConnectedLocked() throws IOException {
        if (isConnectedLocked()) return;

        if (serverIp == null || serverPort <= 0) {
            throw new IOException("Server address not set");
        }

        // Thử reconnect
        socket = new Socket(serverIp, serverPort);
        socket.setTcpNoDelay(true);
        reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true);
        System.out.println("✅ Reconnected to server " + serverIp + ":" + serverPort);
    }

    public void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private void closeLocked() {
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) { }

        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) { }

        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) { }

        writer = null;
        reader = null;
        socket = null;

        System.out.println("🔌 Connection closed");
    }
}
