package org.openstatic.routeput;

import org.json.JSONArray;

public class RoutePutException extends Throwable
{
    public RoutePutException(String errorMessage) {
        super(errorMessage);
    }

    public RoutePutException(String errorMessage, Throwable cause) {
        super(errorMessage, cause);
    }

    public RoutePutMessage toRoutePutMessage()
    {
        RoutePutMessage msg = new RoutePutMessage();
        msg.setType(RoutePutMessage.TYPE_LOG_ERROR);
        msg.put("text", this.getMessage());
        msg.put("trace", new JSONArray(this.getStackTrace()));
        return msg;
    }
}
