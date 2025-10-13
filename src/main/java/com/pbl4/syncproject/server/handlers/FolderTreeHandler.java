package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.common.model.Folders;
import com.pbl4.syncproject.server.dao.FolderDAO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class FolderTreeHandler implements RequestHandler {

    private final FolderDAO folderDAO;

    public FolderTreeHandler(Connection dbConnection) {
        this.folderDAO = new FolderDAO(dbConnection);
    }
    @Override
    public Response handle(Request req) {
        JsonObject data = req.getData();
        // SỬA ĐỔI: Lấy parentId. Nếu không có, mặc định là ID của thư mục gốc.
        // Giả sử thư mục gốc có ID là 1.
        Integer parentId = data.has("parentId") ? data.get("parentId").getAsInt() : 1;

        Response res = new Response();
        try {
            List<Folders> children;
            // SỬA ĐỔI: Hợp nhất logic. Nếu parentId là 1, chúng ta muốn các thư mục con của nó.
            if (parentId == 1) {
                // Nếu bạn có một thư mục gốc ảo (không có trong DB) và muốn hiển thị các thư mục cấp cao nhất
                children = folderDAO.getChildren(1); // Lấy các thư mục con của thư mục gốc (ID=1)
            } else {
                children = folderDAO.getChildren(parentId);
            }

            if (children != null) { // Không cần kiểm tra !children.isEmpty() nữa, trả về mảng rỗng nếu không có con
                JsonArray array = new JsonArray();
                for (Folders child : children) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("folderId", child.getFolderId());
                    // Đảm bảo parentId không bao giờ là null trong JSON nếu nó có giá trị
                    if (child.getParentId() != null) {
                        obj.addProperty("parentFolderId", child.getParentId());
                    } else {
                        obj.add("parentFolderId", null);
                    }
                    obj.addProperty("folderName", child.getFolderName());

                    // === SỬA ĐỔI QUAN TRỌNG Ở ĐÂY ===
                    // Kiểm tra null cho createdAt
                    if (child.getCreatedAt() != null) {
                        obj.addProperty("createdAt", child.getCreatedAt().toString());
                    } else {
                        obj.add("createdAt", null); // Gửi null nếu không có giá trị
                    }

                    // Kiểm tra null cho lastModified (getUpdatedAt)
                    if (child.getUpdatedAt() != null) {
                        obj.addProperty("lastModified", child.getUpdatedAt().toString());
                    } else {
                        obj.add("lastModified", null); // Gửi null nếu không có giá trị
                    }
                    
                    array.add(obj);
                }
                res.setStatus("success");
                res.setMessage("Folder tree retrieved");
                res.setData(array);
            } else {
                // Trường hợp này gần như không xảy ra nếu DAO trả về danh sách trống thay vì null
                res.setStatus("error");
                res.setMessage("Could not retrieve children for parentId " + parentId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage(e.getMessage());
        }
        return res;
    }
}
