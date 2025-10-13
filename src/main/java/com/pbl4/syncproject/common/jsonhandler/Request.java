package com.pbl4.syncproject.common.jsonhandler;

import com.google.gson.JsonObject;

public class Request {
    private String action;
    private JsonObject data;

    public Request() {}

    public Request(String action, JsonObject data) {
        this.action = action;
        this.data = data;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
    }
}
