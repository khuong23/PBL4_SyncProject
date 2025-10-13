package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Service class để xử lý network communication với server
 * Tách biệt network logic khỏi UI controller
 * Sử dụng ClientConnectionManager để duy trì kết nối duy nhất
 */
public class NetworkService {
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB limit

    private String serverIP;
    private int serverPort;

    /**
     * Constructor - PHẢI set server address sau khi tạo object
     */
    public NetworkService() {
        // Không set default IP/port - phải được set từ login
        this.serverIP = null;
        this.serverPort = 0;
    }

    /**
     * Constructor với custom server IP và port (recommended)
     */
    public NetworkService(String serverIP, int serverPort) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
    }

    /**
     * Set server IP và port (BẮT BUỘC gọi trước khi sử dụng các method khác)
     */
    public void setServerAddress(String serverIP, int serverPort) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        System.out.println("📍 NetworkService configured: " + serverIP + ":" + serverPort);
    }

    /**
     * Kiểm tra xem server address đã được set chưa
     */
    private void validateServerAddress() throws Exception {
        if (serverIP == null || serverIP.trim().isEmpty() || serverPort <= 0) {
            throw new Exception("Server address chưa được thiết lập! Vui lòng gọi setServerAddress() trước.");
        }
    }

    /**
     * Get current server IP
     */
    public String getServerIP() {
        return this.serverIP;
    }

    /**
     * Get current server port
     */
    public int getServerPort() {
        return this.serverPort;
    }

    /**
     * Upload file lên server
     */
    public Response uploadFile(File file, int folderId) throws Exception {
        validateServerAddress(); // Kiểm tra server address trước khi sử dụng

        // Validate file size
        if (file.length() > MAX_FILE_SIZE) {
            throw new Exception("File quá lớn. Kích thước tối đa: " + formatFileSize(MAX_FILE_SIZE));
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

    /**
     * Upload file lên server với default folder (root)
     */
    public Response uploadFile(File file) throws Exception {
        return uploadFile(file, 1); // 1 = root folder (not 0)
    }

    /**
     * Lấy danh sách files và folders từ server
     */
    public Response getFileList(int folderId) throws Exception {
        validateServerAddress();

        JsonObject data = new JsonObject();
        data.addProperty("folderId", folderId);

        Request request = new Request("GET_FILE_LIST", data);
        return sendRequest(request);
    }

    /**
     * Lấy danh sách files từ root folder
     */
    public Response getFileList() throws Exception {
        return getFileList(1); // 1 = root folder (not 0)
    }

    /**
     * Tạo folder trên server
     */
    public Response createFolder(String folderName, int parentFolderId) throws Exception {
        validateServerAddress();

        JsonObject data = new JsonObject();
        data.addProperty("folderName", folderName);
        data.addProperty("parentFolderId", parentFolderId);

        Request request = new Request("CREATE_FOLDER", data);
        return sendRequest(request);
    }

    /**
     * Tạo folder với parent mặc định (root)
     */
    public Response createFolder(String folderName) throws Exception {
        return createFolder(folderName, 1); // 1 = root folder (not 0)
    }

    /**
     * Lấy folder tree từ server (children of specific parent)
     * @param parentId ID của thư mục cha (0 = lấy thư mục gốc)
     */
    public Response getFolderTree(int parentId) throws Exception {
        validateServerAddress();
        JsonObject data = new JsonObject();
        // ID 0 có nghĩa là yêu cầu các thư mục gốc
        if (parentId > 0) {
            data.addProperty("parentId", parentId);
        }
        Request request = new Request("FOLDER_TREE", data);
        return sendRequest(request);
    }
    
    /**
     * Lấy folder tree từ server (children of root folder) - để tương thích ngược
     * ParentId = 1 để lấy các folder con của root (shared, documents, images, videos)
     */
    public Response getFolderTree() throws Exception {
        return getFolderTree(0); // Yêu cầu các thư mục gốc theo mặc định
    }

    /**
     * Download file từ server
     */
    public Response downloadFile(String fileName, int folderId) throws Exception {
        validateServerAddress();

        JsonObject data = new JsonObject();
        data.addProperty("fileName", fileName);
        data.addProperty("folderId", folderId);

        Request request = new Request("DOWNLOAD", data);
        return sendRequest(request);
    }

    /**
     * Test connection to server - sử dụng persistent connection
     */
    public boolean testConnection() {
        try {
            // Kiểm tra xem connection manager có kết nối không
            ClientConnectionManager connectionManager = ClientConnectionManager.getInstance();
            if (!connectionManager.isConnected()) {
                // Thử kết nối nếu chưa có
                if (serverIP != null && serverPort > 0) {
                    connectionManager.connect(serverIP, serverPort);
                    return true;
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Test connection failed: " + e.getMessage());
            return false;
        }
    }    /**
     * Gửi request lên server và nhận response (sử dụng persistent connection)
     */
    private Response sendRequest(Request request) throws Exception {
        // Lấy instance của connection manager
        ClientConnectionManager connectionManager = ClientConnectionManager.getInstance();

        if (!connectionManager.isConnected()) {
            // Cố gắng kết nối lại nếu bị mất kết nối
            if (serverIP != null && serverPort > 0) {
                connectionManager.connect(serverIP, serverPort);
            } else {
                throw new Exception("Mất kết nối tới server và không có thông tin để kết nối lại. Vui lòng đăng nhập lại.");
            }
        }
        
        try {
            String requestJson = JsonUtils.toJson(request);
            if (requestJson == null || requestJson.trim().isEmpty()) {
                throw new Exception("Lỗi tạo request JSON");
            }
            
            System.out.println("DEBUG: Sending request (persistent): " + requestJson.substring(0, Math.min(100, requestJson.length())));
            
            // Sử dụng manager để gửi và nhận
            String responseStr = connectionManager.sendRequestAndGetResponse(requestJson);

            if (responseStr == null) {
                throw new Exception("Server không phản hồi hoặc đã ngắt kết nối");
            }

            System.out.println("DEBUG: Received response (persistent): " + responseStr.substring(0, Math.min(100, responseStr.length())));

            // Parse response
            Response response = JsonUtils.fromJson(responseStr, Response.class);
            if (response == null) {
                throw new Exception("Không thể phân tích phản hồi từ server");
            }

            return response;

        } catch (IOException e) {
            // Khi có lỗi IO, đóng kết nối để buộc tạo kết nối mới lần sau
            connectionManager.close();
            throw new Exception("Lỗi kết nối mạng: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Không thể")) {
                throw e; // Re-throw our custom messages
            }
            throw new Exception("Lỗi không xác định: " + e.getMessage(), e);
        }
    }

    /**
     * Get current server info from connection manager
     */
    public String getServerInfo() {
        ClientConnectionManager connectionManager = ClientConnectionManager.getInstance();
        String connectionInfo = connectionManager.getServerInfo();
        if (!"Not connected".equals(connectionInfo)) {
            return connectionInfo;
        }
        return serverIP + ":" + serverPort; // Fallback to local info
    }

    /**
     * Format file size in human readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}