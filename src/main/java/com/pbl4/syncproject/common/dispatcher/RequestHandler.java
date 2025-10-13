package com.pbl4.syncproject.common.dispatcher;

import com.pbl4.syncproject.common.jsonhandler.Request;
import com.pbl4.syncproject.common.jsonhandler.Response;

public interface RequestHandler {
    Response handle(Request req);
}
