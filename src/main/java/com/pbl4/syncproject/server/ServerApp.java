package com.pbl4.syncproject.server;

import com.pbl4.syncproject.server.dao.DatabaseManager;
import com.pbl4.syncproject.server.handlers.ClientHandler;

import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;

public class ServerApp {
    private static final int PORT = 8080; // Changed from 8080 to avoid conflict

    public static void main(String[] args) {
        // Test database connection first
        System.out.println("Testing database connection...");
        try {
            Connection connection = DatabaseManager.getConnection();
            System.out.println("✅ Database connection test successful!");
            
            // Test a query
            var stmt = connection.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) as count FROM Users");
            if (rs.next()) {
                System.out.println("✅ Users in database: " + rs.getInt("count"));
            }
            stmt.close();
        } catch (Exception e) {
            System.err.println("❌ Database connection failed: " + e.getMessage());
            e.printStackTrace();
            return; // Don't start server if database is not working
        }
        
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
