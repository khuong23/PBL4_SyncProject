package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.UserDAO;

public class LoginHandler implements RequestHandler {

    @Override
    public Response handle(Request req) {
        Response res = new Response();

        try {
            JsonObject data = req.getData();
            if (data == null || !data.has("username") || !data.has("password")) {
                res.setStatus("error");
                res.setMessage("Missing username or password");
                return res;
            }

            String username = data.get("username").getAsString().trim();
            String password = data.get("password").getAsString(); // hash sau

            boolean ok = UserDAO.checkLogin(username, password);

            if (ok) {
                // Lấy userId để trả về cho client
                int userId = UserDAO.getUserIdByUsername(username);
                
                JsonObject responseData = new JsonObject();
                responseData.addProperty("userId", userId);
                responseData.addProperty("username", username);
                responseData.addProperty("isAdmin", UserDAO.isAdmin(userId));
                
                res.setStatus("success");
                res.setMessage("Login successful");
                res.setData(responseData);
            } else {
                res.setStatus("error");
                res.setMessage("Invalid username or password");
            }
            return res;

        } catch (Exception e) {
            res.setStatus("error");
            res.setMessage("Login failed: " + e.getMessage());
            return res;
        }
    }
}
