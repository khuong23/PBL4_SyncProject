package com.pbl4.syncproject.server;

import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.handlers.ClientHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ServerApp {
    private static final int PORT = 8080;
    private static final int MAX_THREADS = 32;

    public static void main(String[] args) {
        System.out.println("[INFO]  Booting server...");
        System.out.println("[INFO]  Database: " + (DatabaseManager.ping() ? "OK" : "NOT REACHABLE"));

        ExecutorService pool = Executors.newFixedThreadPool(MAX_THREADS);

        try {
            final ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("[INFO]  Server started on port " + PORT + " (threads=" + MAX_THREADS + ")");

            // Shutdown hook: chỉ đóng socket + thread pool. KHÔNG đóng DB pool (DatabaseManager tự làm).
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[INFO] Shutdown requested. Closing server...");
                try { serverSocket.close(); } catch (IOException ignore) {}
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                System.out.println("[INFO] Server stopped cleanly.");
            }, "shutdown-hook"));

            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("[INFO]  Client connected: " + socket.getRemoteSocketAddress());
                    pool.execute(new ClientHandler(socket));
                } catch (SocketException se) {
                    if (serverSocket.isClosed()) {
                        System.out.println("[INFO] Server socket closed. Exiting accept loop.");
                        break;
                    } else {
                        System.err.println("[ERROR] Socket exception: " + se.getMessage());
                        se.printStackTrace(System.err);
                    }
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
