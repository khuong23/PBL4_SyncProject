package com.pbl4.syncproject.common.dispatcher;

import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.handlers.*;

import java.util.HashMap;
import java.util.Map;

public class Dispatcher {
    private final Map<String, RequestHandler> handlers = new HashMap<>();

    public Dispatcher() {
        // Các handler đã được refactor để tự lấy Connection từ pool, không cần truyền Connection vào
        handlers.put("LOGIN",          new LoginHandler());
        handlers.put("FOLDER_TREE",    new FolderTreeHandler());
        handlers.put("GET_FILE_LIST",  new FileListHandler());
        handlers.put("UPLOAD_FILE",    new UploadFileHandler());
        handlers.put("CREATE_FOLDER",  new CreateFolderHandler());
        handlers.put("DOWNLOAD_FILE",  new DownloadFileHandler());
        handlers.put("DELETE_FOLDER",  new DeleteFolderHandler());
        handlers.put("DELETE_FILE",  new DeleteFileHandler());

        // PING: kiểm tra kết nối đơn giản
        handlers.put("PING", req -> {
            Response res = new Response();
            res.setStatus("success");
            res.setMessage("Server is online and responding");
            return res;
        });
    }

    public Response dispatch(Request req) {
        if (req == null || req.getAction() == null) {
            Response res = new Response();
            res.setStatus("error");
            res.setMessage("Missing action");
            return res;
        }

        String action = req.getAction().trim().toUpperCase();
        RequestHandler handler = handlers.get(action);

        if (handler == null) {
            Response res = new Response();
            res.setStatus("error");
            res.setMessage("Unknown action: " + action);
            return res;
        }

        return handler.handle(req);
    }
}
