package com.pbl4.syncproject.server.handlers;

import com.pbl4.syncproject.common.dispatcher.Dispatcher;
import com.pbl4.syncproject.common.jsonhandler.JsonUtils;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private final Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        final Dispatcher dispatcher = new Dispatcher();

        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out   = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    System.out.println("[INFO]  Received: " + line);

                    Request req = JsonUtils.fromJson(line, Request.class);
                    Response res = dispatcher.dispatch(req);

                    String responseJson = JsonUtils.toJson(res);
                    System.out.println("[INFO]  Sending response: " + responseJson);
                    out.println(responseJson);
                    out.flush();

                } catch (Exception ex) {
                    System.err.println("[ERROR] Bad request: " + ex.getMessage());
                    ex.printStackTrace(System.err);

                    Response err = new Response("error", "Bad request: " + ex.getMessage(), null);
                    String errJson = JsonUtils.toJson(err);
                    System.out.println("[INFO]  Sending error response: " + errJson);
                    out.println(errJson);
                    out.flush();
                }
            }
        } catch (IOException io) {
            System.err.println("[ERROR] IO error: " + io.getMessage());
            io.printStackTrace(System.err);
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    System.out.println("[INFO]  Client disconnected: " + socket.getRemoteSocketAddress());
                }
            } catch (IOException e) {
                System.err.println("[ERROR] Closing socket: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }
}
