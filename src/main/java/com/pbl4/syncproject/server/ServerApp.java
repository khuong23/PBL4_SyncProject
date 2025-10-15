package com.pbl4.syncproject.server;

import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.handlers.ClientHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerApp {
    private static final int PORT = 8080;
    private static final int MAX_THREADS = 32;

    public static void main(String[] args) {
        System.out.println("[INFO]  Booting server...");
        System.out.println("[INFO]  Database: " + (DatabaseManager.ping() ? "OK" : "NOT REACHABLE"));

        ExecutorService pool = Executors.newFixedThreadPool(MAX_THREADS);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[INFO]  Server started on port " + PORT + " (threads=" + MAX_THREADS + ")");

            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("[INFO]  Client connected: " + socket.getRemoteSocketAddress());
                    pool.execute(new ClientHandler(socket));
                } catch (IOException e) {
                    System.err.println("[ERROR] Accept error: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to start server: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
