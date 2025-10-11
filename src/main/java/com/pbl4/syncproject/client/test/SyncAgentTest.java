package com.pbl4.syncproject.client.test;

import com.pbl4.syncproject.client.services.SyncAgent;
import com.pbl4.syncproject.client.services.NetworkService;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test class để test SyncAgent functionality
 */
public class SyncAgentTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== SyncAgent Test ===");
            
            // Create network service
            NetworkService networkService = new NetworkService();
            
            // Create sync agent
            SyncAgent syncAgent = new SyncAgent(networkService);
            
            // Test directory
            String testDir = System.getProperty("user.home") + File.separator + "SyncTest";
            File dir = new File(testDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            System.out.println("Starting sync agent for: " + testDir);
            syncAgent.start(testDir);
            
            // Create test file
            Thread.sleep(2000);
            System.out.println("Creating test file...");
            File testFile = new File(testDir, "test_sync.txt");
            try (FileWriter writer = new FileWriter(testFile)) {
                writer.write("Test file for sync agent - " + System.currentTimeMillis());
            }
            
            // Wait and check status
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                SyncAgent.SyncStatus status = syncAgent.getStatus();
                System.out.println("Status: " + status);
            }
            
            // Modify file
            Thread.sleep(2000);
            System.out.println("Modifying test file...");
            try (FileWriter writer = new FileWriter(testFile, true)) {
                writer.write("\nModified at: " + System.currentTimeMillis());
            }
            
            // Wait and check status again
            for (int i = 0; i < 5; i++) {
                Thread.sleep(1000);
                SyncAgent.SyncStatus status = syncAgent.getStatus();
                System.out.println("Status after modify: " + status);
            }
            
            System.out.println("Stopping sync agent...");
            syncAgent.stop();
            
            System.out.println("Test completed!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}