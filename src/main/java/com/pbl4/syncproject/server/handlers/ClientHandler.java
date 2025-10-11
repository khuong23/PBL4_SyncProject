package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.Dispatcher;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.DatabaseManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Dispatcher dispatcher;
    private final Connection dbConnection;

    public ClientHandler(Socket socket) throws Exception {
        this.socket = socket;
        this.dbConnection = DatabaseManager.getConnection();
        this.dispatcher = new Dispatcher(dbConnection);
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        ) {

            String input;
            while ((input = in.readLine()) != null) {
                try {
                    System.out.println("Received: " + input);
                    Request req = JsonUtils.fromJson(input, Request.class);
                    Response res = dispatcher.dispatch(req);
                    out.println(JsonUtils.toJson(res));
                    out.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                    Response error = new Response("error", e.getMessage(), null);
                    out.println(JsonUtils.toJson(error));
                    out.flush();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (dbConnection != null && !dbConnection.isClosed()) {
                    dbConnection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}