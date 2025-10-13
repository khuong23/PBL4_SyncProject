package com.pbl4.syncproject.client.utils;

import com.pbl4.syncproject.client.views.IMainView;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Utility class để wrap Task operations và simplify UI updates
 * Giảm boilerplate code trong Controller
 */
public class TaskWrapper {
    
    /**
     * Execute background task với automatic UI updates
     */
    public static <T> void executeAsync(
            String loadingMessage,
            Supplier<T> backgroundTask,
            Consumer<T> onSuccess,
            Consumer<String> onError,
            IMainView mainView) {
        
        // Set loading message
        if (loadingMessage != null) {
            mainView.setStatusMessage(loadingMessage);
        }
        
        Task<T> task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                return backgroundTask.get();
            }
        };
        
        task.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                try {
                    T result = task.getValue();
                    onSuccess.accept(result);
                } catch (Exception ex) {
                    onError.accept("Lỗi xử lý kết quả: " + ex.getMessage());
                }
            });
        });
        
        task.setOnFailed(e -> {
            Platform.runLater(() -> {
                Throwable exception = task.getException();
                String errorMsg = exception != null ? exception.getMessage() : "Lỗi không xác định";
                onError.accept(errorMsg);
            });
        });
        
        new Thread(task).start();
    }
    
    /**
     * Execute simple background task với default error handling
     */
    public static <T> void executeAsync(
            String loadingMessage,
            Supplier<T> backgroundTask,
            Consumer<T> onSuccess,
            IMainView mainView) {
        
        executeAsync(loadingMessage, backgroundTask, onSuccess, 
            errorMsg -> {
                mainView.setStatusMessage("Lỗi: " + errorMsg);
                mainView.showAlert("Lỗi", errorMsg, IMainView.AlertType.ERROR);
            }, mainView);
    }
    
    /**
     * Execute task với progress tracking
     */
    public static <T> void executeAsyncWithProgress(
            String loadingMessage,
            TaskWithProgress<T> backgroundTask,
            Consumer<T> onSuccess,
            Consumer<String> onError,
            IMainView mainView) {
        
        if (loadingMessage != null) {
            mainView.setStatusMessage(loadingMessage);
        }
        
        Task<T> task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                return backgroundTask.call(this::updateProgress, this::updateMessage);
            }
        };
        
        // Optional: bind progress to UI if needed
        task.progressProperty().addListener((obs, oldProgress, newProgress) -> {
            // Can be used for progress bars
        });
        
        task.messageProperty().addListener((obs, oldMessage, newMessage) -> {
            if (newMessage != null) {
                mainView.setStatusMessage(newMessage);
            }
        });
        
        task.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                try {
                    T result = task.getValue();
                    onSuccess.accept(result);
                } catch (Exception ex) {
                    onError.accept("Lỗi xử lý kết quả: " + ex.getMessage());
                }
            });
        });
        
        task.setOnFailed(e -> {
            Platform.runLater(() -> {
                Throwable exception = task.getException();
                String errorMsg = exception != null ? exception.getMessage() : "Lỗi không xác định";
                onError.accept(errorMsg);
            });
        });
        
        new Thread(task).start();
    }
    
    /**
     * Interface cho tasks có progress
     */
    @FunctionalInterface
    public interface TaskWithProgress<T> {
        T call(ProgressUpdater progressUpdater, MessageUpdater messageUpdater) throws Exception;
    }
    
    @FunctionalInterface
    public interface ProgressUpdater {
        void updateProgress(double workDone, double totalWork);
    }
    
    @FunctionalInterface
    public interface MessageUpdater {
        void updateMessage(String message);
    }
}