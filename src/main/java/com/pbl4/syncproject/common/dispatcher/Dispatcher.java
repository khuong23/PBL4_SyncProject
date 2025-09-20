package com.pbl4.syncproject.common.dispatcher;

import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.handlers.FolderTreeHandler;
import com.pbl4.syncproject.server.handlers.LoginHandler;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

public class Dispatcher {
    private final Map<String, RequestHandler> handlers = new HashMap<>();

    public Dispatcher(Connection dbConnection) {
        handlers.put("LOGIN", new LoginHandler(dbConnection));
        handlers.put("FOLDER_TREE", new FolderTreeHandler(dbConnection) );
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
