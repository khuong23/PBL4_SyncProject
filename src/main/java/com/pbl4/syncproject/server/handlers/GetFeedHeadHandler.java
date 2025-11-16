package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.ChangesDAO;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.sql.Connection;

/**
 * Handler để lấy lastSeq hiện tại từ bảng Changes
 * Được sử dụng bởi client để cập nhật since_seq
 */
public class GetFeedHeadHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        try (Connection conn = DatabaseManager.getConnection()) {
            // Lấy lastSeq hiện tại
            long lastSeq = ChangesDAO.getLastSeq(conn);
            
            // Trả về trong data
            JsonObject data = new JsonObject();
            data.addProperty("lastSeq", lastSeq);
            data.addProperty("head", lastSeq); // Alias cho lastSeq
            
            return new Response("success", "Feed head retrieved", data);
            
        } catch (Exception e) {
            e.printStackTrace();
            return new Response("error", "Lỗi lấy feed head: " + e.getMessage(), null);
        }
    }
}
