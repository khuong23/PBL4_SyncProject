package com.pbl4.syncproject.client.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Singleton class để quản lý kết nối mạng duy nhất trong suốt phiên làm việc
 * Giải quyết vấn đề tạo kết nối mới cho mỗi yêu cầu
 */
public class ClientConnectionManager {
    private static ClientConnectionManager instance;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private String serverIP;
    private int serverPort;
    
    // Private constructor để đảm bảo singleton
    private ClientConnectionManager() {}

    /**
     * Lấy instance singleton của ConnectionManager
     */
    public static synchronized ClientConnectionManager getInstance() {
        if (instance == null) {
            instance = new ClientConnectionManager();
        }
        return instance;
    }

    /**
     * Thiết lập kết nối tới server
     * @param ip địa chỉ IP của server
     * @param port cổng của server
     * @throws IOException nếu không thể kết nối
     */
    public synchronized void connect(String ip, int port) throws IOException {
        if (socket != null && !socket.isClosed()) {
            // Đã kết nối, có thể kiểm tra xem có cần đóng kết nối cũ không
            System.out.println("🔄 Kết nối hiện tại vẫn hoạt động: " + serverIP + ":" + serverPort);
            return;
        }
        
        this.serverIP = ip;
        this.serverPort = port;
        socket = new Socket(ip, port);
        writer = new PrintWriter(socket.getOutputStream(), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        
        System.out.println("✅ Kết nối liên tục được thiết lập tới " + ip + ":" + port);
    }

    /**
     * Kiểm tra trạng thái kết nối
     * @return true nếu đang kết nối
     */
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    /**
     * Gửi yêu cầu và nhận phản hồi (thread-safe)
     * @param jsonRequest yêu cầu JSON
     * @return phản hồi từ server
     * @throws IOException nếu có lỗi mạng
     */
    public synchronized String sendRequestAndGetResponse(String jsonRequest) throws IOException {
        if (!isConnected()) {
            throw new IOException("Không có kết nối tới server. Vui lòng đăng nhập lại.");
        }
        
        try {
            writer.println(jsonRequest);
            String response = reader.readLine();
            
            if (response == null) {
                throw new IOException("Server đã đóng kết nối");
            }
            
            return response;
        } catch (IOException e) {
            // Khi có lỗi, đóng kết nối để buộc tạo kết nối mới lần sau
            close();
            throw e;
        }
    }

    /**
     * Lấy thông tin server hiện tại
     * @return chuỗi "IP:Port" hoặc "Not connected"
     */
    public String getServerInfo() {
        if (isConnected()) {
            return serverIP + ":" + serverPort;
        }
        return "Not connected";
    }

    /**
     * Đóng kết nối
     */
    public synchronized void close() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
            System.out.println("🔌 Kết nối đã được đóng.");
        } catch (IOException e) {
            System.err.println("⚠️ Lỗi khi đóng kết nối: " + e.getMessage());
        } finally {
            socket = null;
            writer = null;
            reader = null;
        }
    }

    /**
     * Reconnect nếu kết nối bị mất
     * @throws IOException nếu không thể kết nối lại
     */
    public synchronized void reconnect() throws IOException {
        if (serverIP == null || serverPort <= 0) {
            throw new IOException("Không có thông tin server để kết nối lại");
        }
        
        close(); // Đóng kết nối cũ
        connect(serverIP, serverPort); // Tạo kết nối mới
    }
}