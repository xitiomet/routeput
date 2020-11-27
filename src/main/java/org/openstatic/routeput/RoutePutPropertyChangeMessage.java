package org.openstatic.routeput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.json.JSONObject;
import org.openstatic.routeput.util.JSONTools;

public class RoutePutPropertyChangeMessage extends RoutePutMessage
{
    public static final String TYPE_CHANNEL = "channel";
    public static final String TYPE_SESSION = "session";

    public RoutePutPropertyChangeMessage(RoutePutMessage rpm)
    {
        super();
        this.setType(RoutePutMessage.TYPE_PROPERTY_CHANGE);
        this.setSourceId(rpm.getSourceId());
        this.getRoutePutMeta().put("updates", rpm.getRoutePutMeta().optJSONArray("updates"));
    }

    public RoutePutPropertyChangeMessage forChannel(RoutePutChannel channel)
    {
        RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
        List<Object> updates = JSONTools.listJSONArray(this.getRoutePutMeta().optJSONArray("updates"));
        updates.forEach((update) -> {
            try
            {
                if (update instanceof JSONObject)
                {
                    JSONObject joUpdate = (JSONObject) update;
                    String objectType = joUpdate.optString("type");
                    String objectId = joUpdate.optString("id");
                    if (TYPE_CHANNEL.equals(objectType))
                    {
                        if (channel.getName().equals(objectId))
                        {
                            rppcm.addUpdate((JSONObject) update);
                        }
                    } else if (TYPE_SESSION.equals(objectType)) {
                        if (channel.hasMember(objectId))
                        {
                            rppcm.addUpdate((JSONObject) update);
                        }
                    }
                }
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        });
        rppcm.setSourceId(this.getSourceId());
        rppcm.setChannel(channel);
        return rppcm;
    }

    public RoutePutPropertyChangeMessage()
    {
        super();
        this.setType(RoutePutMessage.TYPE_PROPERTY_CHANGE);
    }

    public void processUpdates(RoutePutSession receivingSession)
    {
        List<Object> updates = JSONTools.listJSONArray(this.getRoutePutMeta().optJSONArray("updates"));
        final ArrayList<RoutePutChannel> channelsInvolved = new ArrayList<RoutePutChannel>();
        updates.forEach((update) -> {
            try
            {
                if (update instanceof JSONObject)
                {
                    JSONObject joUpdate = (JSONObject) update;
                    String objectType = joUpdate.optString("type");
                    String objectId = joUpdate.optString("id");
                    String key = joUpdate.optString("key");
                    Object oldValue = joUpdate.opt("old");
                    Object newValue = joUpdate.opt("new");
                    if (TYPE_CHANNEL.equals(objectType))
                    {
                        RoutePutChannel channel = RoutePutChannel.getChannel(objectId);
                        channel.firePropertyChange(key, oldValue, newValue);
                        if (!channelsInvolved.contains(channel))
                            channelsInvolved.add(channel);
                    } else if (TYPE_SESSION.equals(objectType)) {
                        boolean handled = false;
                        if (receivingSession != null)
                        {
                            if (receivingSession.getConnectionId().equals(objectId))
                            {
                                receivingSession.firePropertyChange(key, oldValue, newValue);
                                Collection<RoutePutChannel> rpcc = RoutePutChannel.channelsWithMember(receivingSession);
                                rpcc.forEach((channel) -> {
                                    if (!channelsInvolved.contains(channel))
                                        channelsInvolved.add(channel);
                                });
                                handled = true;
                            }
                        }
                        if (!handled)
                        {
                            RoutePutRemoteSession rprs = RoutePutRemoteSession.findRemoteSession(objectId);
                            if (rprs != null)
                            {
                                rprs.firePropertyChange(key, oldValue, newValue);
                                Collection<RoutePutChannel> rpcc = RoutePutChannel.channelsWithMember(rprs);
                                rpcc.forEach((channel) -> {
                                    if (!channelsInvolved.contains(channel))
                                        channelsInvolved.add(channel);
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        });
        channelsInvolved.forEach((channel) -> {
            channel.broadcast(this.forChannel(channel));
        });
    }

    public RoutePutPropertyChangeMessage addUpdate(RoutePutChannel channel, String key, Object oldValue, Object newValue)
    {
        JSONObject update = new JSONObject();
        update.put("type", TYPE_CHANNEL);
        update.put("id", channel.getName());
        update.put("key", key);
        update.put("old", oldValue);
        update.put("new", newValue);
        return this.addUpdate(update);
    }

    public RoutePutPropertyChangeMessage addUpdate(RoutePutSession session, String key, Object oldValue, Object newValue)
    {
        JSONObject update = new JSONObject();
        update.put("type", TYPE_SESSION);
        update.put("id", session.getConnectionId());
        update.put("key", key);
        update.put("old", oldValue);
        update.put("new", newValue);
        return this.addUpdate(update);
    }

    public RoutePutPropertyChangeMessage addUpdate(JSONObject update)
    {
        this.appendMetaArray("updates", update);
        return this;
    }
}
