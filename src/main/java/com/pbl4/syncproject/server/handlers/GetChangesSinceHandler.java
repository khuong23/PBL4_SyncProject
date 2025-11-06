package com.pbl4.syncproject.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pbl4.syncproject.common.dispatcher.RequestHandler;
import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;
import com.pbl4.syncproject.server.dao.ChangesDAO;
import com.pbl4.syncproject.server.dao.DatabaseManager;

public class GetChangesSinceHandler implements RequestHandler {

    @Override
    public Response handle(Request request) {
        try (var c = DatabaseManager.getConnection()) {
            long sinceSeq = request.getData().get("sinceSeq").getAsLong();
            long lastSeq = ChangesDAO.getLastSeq(c);
            var list = ChangesDAO.getSince(c, sinceSeq);

            JsonObject body = new JsonObject();
            body.addProperty("lastSeq", lastSeq);
            var arr = new JsonArray();
            for (var ch : list) arr.add(ch);
            body.add("changes", arr);
            return new Response("success", "", body);
        } catch (Exception e) {
            return new Response("error", e.getMessage(), null);
        }
    }

}
