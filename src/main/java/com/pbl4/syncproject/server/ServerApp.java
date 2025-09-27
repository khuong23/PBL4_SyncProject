package com.pbl4.syncproject.server;

import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.handlers.ClientHandler;

import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;

public class ServerApp {
    private static final int PORT = 808;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected: " + socket.getInetAddress());

                ClientHandler handler = new ClientHandler(socket);
                new Thread(handler).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
