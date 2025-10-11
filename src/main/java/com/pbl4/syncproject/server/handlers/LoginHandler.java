package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.UserDAO;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.sql.Connection;

public class LoginHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        JsonObject data = req.getData();
        String username = data.get("username").getAsString();
        String password = data.get("password").getAsString();

        Response res = new Response();
        
        // Tạo connection riêng cho mỗi request
        try (Connection connection = DatabaseManager.getConnection()) {
            UserDAO userDAO = new UserDAO();
            
            if (userDAO.checkLogin(connection, username, password)) {
                res.setStatus("success");
                res.setMessage("Login successful");
            } else {
                res.setStatus("error");
                res.setMessage("Invalid username or password");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            res.setStatus("error");
            res.setMessage("Database connection failed: " + e.getMessage());
        }
        
        return res;
    }
}
