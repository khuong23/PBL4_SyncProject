package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class GetUserListHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();
        JsonArray arr = new JsonArray();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT UserID, Username FROM Users ORDER BY Username ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JsonObject o = new JsonObject();
                o.addProperty("userId", rs.getInt("UserID"));
                o.addProperty("username", rs.getString("Username"));
                arr.add(o);
            }
            res.setStatus("success");
            res.setMessage("User list retrieved");
            res.setData(arr);
        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Failed to get user list: " + e.getMessage());
        }
        return res;
    }
}
