package com.pbl4.syncproject.server.handlers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ClientHandler implements Runnable {
    private Socket socket;
    private Connection dbConnection;

    public ClientHandler(Socket socket, Connection dbConnection) {
        this.socket = socket;
        this.dbConnection = dbConnection;
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            // Read the single line and split into username and password
            String input = in.readLine();
            if (input != null) {
                String[] credentials = input.split(",");
                if (credentials.length == 2) {
                    String username = credentials[0].trim();
                    String password = credentials[1].trim();

                    System.out.println("📥 Received login: " + username);

                    if (checkLogin(username, password)) {
                        out.println("SUCCESS");
                        System.out.println("✅ Login success: " + username);
                    } else {
                        out.println("FAIL");
                        System.out.println("❌ Login failed: " + username);
                    }
                } else {
                    out.println("FAIL");
                    System.out.println("❌ Invalid input format: " + input);
                }
            } else {
                out.println("FAIL");
                System.out.println("❌ No input received");
            }

        } catch (IOException e) {
            System.err.println("⚠️ Client error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("⚠️ Could not close socket: " + e.getMessage());
            }
        }
    }

    private boolean checkLogin(String username, String password) {
        try {
            String sql = "SELECT * FROM users WHERE Username=? AND PasswordHash=?";
            PreparedStatement stmt = dbConnection.prepareStatement(sql);
            stmt.setString(1, username);
            stmt.setString(2, password); // Note: In production, hash passwords

            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (Exception e) {
            System.err.println("DB error: " + e.getMessage());
            return false;
        }
    }
}