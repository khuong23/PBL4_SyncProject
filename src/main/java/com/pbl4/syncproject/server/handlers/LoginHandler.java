package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.server.dao.UserDAO;

import java.sql.Connection;

public class LoginHandler implements RequestHandler {
    private final UserDAO userDAO;

    public LoginHandler(Connection connection) {
        this.userDAO = new UserDAO(connection);
    }

    @Override
    public Response handle(Request req) {
        JsonObject data = req.getData();
        String username = data.get("username").getAsString();
        String password = data.get("password").getAsString();

        Response res = new Response();
        if (userDAO.checkLogin(username, password)) {
            res.setStatus("success");
            res.setMessage("Login successful");
        } else {
            res.setStatus("error");
            res.setMessage("Invalid username or password");
        }
        return res;
    }
}
