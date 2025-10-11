package com.pbl4.syncproject.client.views;

import com.pbl4.syncproject.client.models.FileItem;
import javafx.collections.ObservableList;

/**
 * Interface định nghĩa contract giữa MainController và MainView
 * Tách biệt business logic khỏi presentation logic
 */
public interface IMainView {

    // User Information Methods
    void setUserInfo(String userInfo);
    void setConnectionStatus(String status, boolean isConnected);
    void setNetworkStatus(String status, boolean isConnected);

    // Status and Progress Methods
    void setStatusMessage(String message);
    void setSyncStatus(String status, boolean isSuccess);
    void setSyncProgress(double progress, String message, boolean visible);
    void setFileCount(int count);
    void setSelectedItemsInfo(String info);

    // File Management Methods
    void updateFileList(ObservableList<FileItem> fileItems);
    void refreshFileList();
    void clearSelection();
    FileItem getSelectedFile();
    ObservableList<FileItem> getSelectedFiles();

    // Directory Tree Methods
    void updateDirectoryTree();
    void selectDirectory(String directory);
    String getCurrentDirectory();

    // Search and Filter Methods
    void filterFiles(String searchText);
    void sortFiles(String sortBy);
    void changeViewMode(String viewMode);

    // Dialog Methods
    void showAlert(String title, String message, AlertType type);
    void showFileProperties(FileItem fileItem);
    boolean showConfirmDialog(String title, String message);
    String showInputDialog(String title, String message, String defaultValue);

    // File Operations UI
    void showUploadProgress(String fileName, double progress);
    void hideUploadProgress();
    void showDownloadProgress(String fileName, double progress);
    void hideDownloadProgress();

    // UI State Methods
    void enableUI(boolean enabled);
    void setButtonsEnabled(boolean enabled);

    // Search Methods
    String getSearchText();
    void clearSearchText();

    // ComboBox Values
    String getSelectedViewMode();
    void setSelectedViewMode(String viewMode);
    String getSelectedSortBy();
    void setSelectedSortBy(String sortBy);

    // Event Handler Registration
    void setOnLogout(Runnable handler);
    void setOnRefresh(Runnable handler);
    void setOnUpload(Runnable handler);
    void setOnCreateFolder(Runnable handler);
    void setOnPermissions(Runnable handler);
    void setOnSettings(Runnable handler);
    void setOnSearch(Runnable handler);
    void setOnDirectorySelected(DirectorySelectionHandler handler);
    void setOnFileSelected(FileSelectionHandler handler);
    void setOnFileDoubleClick(FileActionHandler handler);

    // Functional Interfaces for event handling
    @FunctionalInterface
    interface DirectorySelectionHandler {
        void onDirectorySelected(com.pbl4.syncproject.common.model.Folders folder);
    }

    @FunctionalInterface
    interface FileSelectionHandler {
        void onFileSelected(FileItem fileItem);
    }

    @FunctionalInterface
    interface FileActionHandler {
        void onFileAction(FileItem fileItem, String action);
    }

    // Alert Type enum
    enum AlertType {
        INFORMATION, WARNING, ERROR, CONFIRMATION
    }
}