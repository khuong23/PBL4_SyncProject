package com.pbl4.syncproject.client.services;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Service class để xử lý network communication với server
 * Tách biệt network logic khỏi UI controller
 */
public class NetworkService {
    private static final String SERVER_IP = "20.89.65.146";
    private static final int SERVER_PORT = 8080;
    private static final int TIMEOUT_MS = 30000; // 30 seconds
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB limit
    
    /**
     * Upload file lên server
     */
    public Response uploadFile(File file, int folderId) throws Exception {
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
            
            Request request = new Request("UPLOAD", data);
            
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
        return uploadFile(file, 0); // 1 = root folder (not 0)
    }
    
    /**
     * Lấy danh sách files và folders từ server
     */
    public Response getFileList(int folderId) throws Exception {
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
     * Download file từ server
     */
    public Response downloadFile(String fileName, int folderId) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("fileName", fileName);
        data.addProperty("folderId", folderId);
        
        Request request = new Request("DOWNLOAD", data);
        return sendRequest(request);
    }
    
    /**
     * Test connection tới server
     */
    public boolean testConnection() {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("test", "ping");
            data.addProperty("timestamp", System.currentTimeMillis());
            
            Request request = new Request("PING", data);
            Response response = sendRequest(request);
            
            return response != null && "success".equals(response.getStatus());
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Gửi request lên server và nhận response
     */
    private Response sendRequest(Request request) throws Exception {
        Socket socket = null;
        PrintWriter writer = null;
        BufferedReader reader = null;
        
        try {
            // Create connection with timeout
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(SERVER_IP, SERVER_PORT), 10000); // 10s connect timeout
            socket.setSoTimeout(TIMEOUT_MS);
            
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            // Gửi request
            String requestJson = JsonUtils.toJson(request);
            if (requestJson == null || requestJson.trim().isEmpty()) {
                throw new Exception("Lỗi tạo request JSON");
            }
            
            writer.println(requestJson);
            writer.flush();
            
            // Check for write errors
            if (writer.checkError()) {
                throw new Exception("Lỗi gửi dữ liệu lên server");
            }
            
            // Đọc response với timeout
            String responseStr = reader.readLine();
            if (responseStr == null) {
                throw new Exception("Server không phản hồi hoặc đã ngắt kết nối");
            }
            
            if (responseStr.trim().isEmpty()) {
                throw new Exception("Server trả về phản hồi rỗng");
            }
            
            // Parse response
            Response response = JsonUtils.fromJson(responseStr, Response.class);
            if (response == null) {
                throw new Exception("Không thể phân tích phản hồi từ server");
            }
            
            return response;
            
        } catch (java.net.ConnectException e) {
            throw new Exception("Không thể kết nối tới server " + SERVER_IP + ":" + SERVER_PORT + 
                              ". Server có thể đang tắt hoặc địa chỉ không đúng.");
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("Kết nối tới server bị timeout. Vui lòng thử lại sau.");
        } catch (java.net.UnknownHostException e) {
            throw new Exception("Không thể tìm thấy server tại địa chỉ: " + SERVER_IP);
        } catch (IOException e) {
            throw new Exception("Lỗi kết nối mạng: " + e.getMessage());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Không thể")) {
                throw e; // Re-throw our custom messages
            }
            throw new Exception("Lỗi không xác định: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            try {
                if (writer != null) writer.close();
                if (reader != null) reader.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Get current server info
     */
    public String getServerInfo() {
        return SERVER_IP + ":" + SERVER_PORT;
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