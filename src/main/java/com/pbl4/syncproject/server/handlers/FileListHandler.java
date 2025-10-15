package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.common.model.Files;
import com.pbl4.syncproject.server.dao.FolderDAO;
import com.pbl4.syncproject.server.dao.FilesDAO;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handler lấy danh sách files/folders từ DB.
 * Quy ước: Root folder có ID = 1.
 */
public class FileListHandler implements RequestHandler {

    // Thread-safe formatter
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    public Response handle(Request request) {
        Response res = new Response();
        try {
            JsonObject data = request.getData();
            int folderId = (data != null && data.has("folderId")) ? data.get("folderId").getAsInt() : 1;
            if (folderId == 0) folderId = 1; // ép về root

            // Lấy dữ liệu từ DAO (DAO dùng Hikari pool bên trong)
            List<Folders> childFolders = FolderDAO.getChildren(folderId);
            List<Files> filesInFolder  = FilesDAO.getFilesInFolder(folderId);

            JsonObject payload = new JsonObject();
            payload.add("folders", toJsonFolders(childFolders));
            payload.add("files",   toJsonFiles(filesInFolder));

            res.setStatus("success");
            res.setMessage("Lấy danh sách thành công");
            res.setData(payload);
            return res;

        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Lỗi khi lấy danh sách: " + e.getMessage());
            return res;
        }
    }

    // ===== Helpers =====

    private JsonArray toJsonFolders(List<Folders> list) {
        JsonArray arr = new JsonArray();
        for (Folders f : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id",   f.getFolderId());
            o.addProperty("name", f.getFolderName());
            o.addProperty("type", "folder");

            var lm = (f.getUpdatedAt() != null) ? f.getUpdatedAt() : f.getCreatedAt();
            o.addProperty("lastModified", lm != null ? DTF.format(lm) : "");

            o.addProperty("size", "");
            o.addProperty("permission", "Đọc/Ghi");
            o.addProperty("syncStatus", "✅ Đã đồng bộ");
            arr.add(o);
        }
        return arr;
    }

    private JsonArray toJsonFiles(List<Files> list) {
        JsonArray arr = new JsonArray();
        for (Files f : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id",   f.getFileId());
            o.addProperty("name", f.getFileName());
            o.addProperty("type", "file");

            // Nếu cần tên folder, tạo thêm DAO JOIN lấy FolderName rồi set vào đây
            o.addProperty("folderName", "");

            o.addProperty("size", formatFileSize(f.getSize()));
            o.addProperty("fileType", getFileType(f.getFileName()));

            var lm = (f.getUpdatedAt() != null) ? f.getUpdatedAt() : f.getCreatedAt();
            o.addProperty("lastModified", lm != null ? DTF.format(lm) : "");

            o.addProperty("permission", "Đọc/Ghi");
            o.addProperty("syncStatus", "✅ Đã đồng bộ");
            arr.add(o);
        }
        return arr;
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "File";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "File";
        String ext = fileName.substring(dot).toLowerCase();

        switch (ext) {
            case ".doc": case ".docx": case ".pdf": case ".txt": case ".rtf":
                return "Document";
            case ".xls": case ".xlsx": case ".csv":
                return "Spreadsheet";
            case ".ppt": case ".pptx":
                return "Presentation";
            case ".jpg": case ".jpeg": case ".png": case ".gif": case ".bmp": case ".tiff":
                return "Image";
            case ".mp4": case ".avi": case ".mkv": case ".mov": case ".wmv":
                return "Video";
            case ".mp3": case ".wav": case ".flac": case ".aac":
                return "Audio";
            case ".zip": case ".rar": case ".7z": case ".tar": case ".gz":
                return "Archive";
            default:
                return "File";
        }
    }
}