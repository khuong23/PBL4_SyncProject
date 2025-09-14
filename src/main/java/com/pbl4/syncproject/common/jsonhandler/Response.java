package com.pbl4.syncproject.common.jsonhandler;

import com.google.gson.JsonElement;

public class Response {
    private String status;   // success | error
    private String message;
    private JsonElement data;

    // Constructor mặc định
    public Response() {}

    public Response(String status, String message, JsonElement data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JsonElement getData() {
        return data;
    }

    public void setData(JsonElement data) {
        this.data = data;
    }
}
