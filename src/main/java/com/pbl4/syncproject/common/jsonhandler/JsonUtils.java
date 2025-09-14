package com.pbl4.syncproject.common.jsonhandler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class JsonUtils {
    private static final Gson gson = new Gson();

    // Chuyển object thành JSON string
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    // Parse JSON string thành object
    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    // Parse về JsonObject (nếu không biết class cụ thể)
    public static JsonObject parseObject(String json) {
        return gson.fromJson(json, JsonObject.class);
    }
}
