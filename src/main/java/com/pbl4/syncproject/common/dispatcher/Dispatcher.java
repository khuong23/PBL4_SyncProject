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

    public Dispatcher(Connection dbConnection) {
        handlers.put("LOGIN", new LoginHandler(dbConnection));
        handlers.put("FOLDER_TREE", new FolderTreeHandler(dbConnection) );
        handlers.put("UPLOAD", new UploadHandle());
        handlers.put("GET_FILE_LIST", new FileListHandler(dbConnection));
        
        // Add PING handler for connection testing
        handlers.put("PING", new RequestHandler() {
            @Override
            public Response handle(Request request) {
                Response response = new Response();
                response.setStatus("success");
                response.setMessage("pong");
                // Echo back the timestamp if provided
                if (request.getData() != null && request.getData().has("timestamp")) {
                    response.setData(request.getData());
                }
                return response;
            }
        });
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
