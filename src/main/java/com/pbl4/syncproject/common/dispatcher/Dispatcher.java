package com.pbl4.syncproject.common.dispatcher;

import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.handlers.FileListHandler;
import com.pbl4.syncproject.server.handlers.FolderTreeHandler;
import com.pbl4.syncproject.server.handlers.LoginHandler;
import com.pbl4.syncproject.server.handlers.UploadHandle;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

public class Dispatcher {
    private final Map<String, RequestHandler> handlers = new HashMap<>();

    public Dispatcher() {
        handlers.put("LOGIN", new LoginHandler());
        handlers.put("FOLDER_TREE", new FolderTreeHandler());
        handlers.put("GET_FILE_LIST", new FileListHandler());
        handlers.put("UPLOAD_FILE", new UploadHandle()); // UploadHandle không nhận Connection parameter
    }

    public Response dispatch(Request req) {
        RequestHandler handler = handlers.get(req.getAction());
        if (handler != null) {
            return handler.handle(req);
        } else {
            Response res = new Response();
            res.setStatus("error");
            res.setMessage("Unknown action: " + req.getAction());
            return res;
        }
    }
}
