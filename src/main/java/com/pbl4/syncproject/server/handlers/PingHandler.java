package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.ChangesDAO;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.sql.Connection;
import java.util.Objects;

public class PingHandler implements RequestHandler {
    @Override
    public Response handle(Request req) {
        Response response = new Response();
        JsonObject data = new JsonObject();
        try(Connection conn = DatabaseManager.getConnection()){
            long lastSeq = ChangesDAO.getLastSeq(conn);
            data.addProperty("lastSeq", lastSeq);
            response.setData(data);
            response.setStatus("success");
            response.setMessage("Ping successful");
        } catch (Exception e) {
            data.addProperty("lastSeq", 0);
            response.setData(data);
            response.setStatus("error");
            response.setMessage("Ping failed: " + e.getMessage());
        };
        return response;
    }
}
