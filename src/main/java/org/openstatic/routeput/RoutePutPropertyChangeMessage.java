package org.openstatic.routeput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.routeput.util.JSONTools;

/*
    This class basically handles all property synchronization.

    There are two types of properties in routeput:
        TYPE_SESSION - linked to a session property
        TYPE_CHANNEL - linked to a channel property

    Property Change Messages can contain a bunch of updates for the channel and its sessions
    The channel of the property change update itself reflects the triggering channel of the updates
*/

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
        // Carry hop history so this relay obeys the same loop guard as every other message.
        JSONArray hops = rpm.getHops();
        if (hops != null)
            this.getRoutePutMeta().put("hops", hops);
    }

    public static List<RoutePutPropertyChangeMessage> buildSmallUpdatesFor(RoutePutChannel channel)
    {
        JSONObject properties = channel.getProperties();
        Set<String> keys = properties.keySet();
        ArrayList<RoutePutPropertyChangeMessage> messages = new ArrayList<RoutePutPropertyChangeMessage>(keys.size());
        keys.forEach((key) -> {
            RoutePutPropertyChangeMessage rppcm = new RoutePutPropertyChangeMessage();
            rppcm.setChannel(channel);
            rppcm.addUpdate(channel, key, null, properties.opt(key));
            messages.add(rppcm);
        });
        return messages;
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
        // Copy prior hops and stamp this host so peers stop relaying the change in circles.
        JSONArray hops = this.getHops();
        if (hops != null)
        {
            for (int i = 0; i < hops.length(); i++)
            {
                String hop = hops.optString(i, null);
                if (hop != null)
                    rppcm.appendHop(hop);
            }
        }
        rppcm.appendHop(RoutePutChannel.getMasterConnectionId());
        return rppcm;
    }

    private boolean nullSafeCompare(Object obj1, Object obj2)
    {
        if (obj1 != null && obj2 != null)
        {
            // neither is null!
            return obj1.equals(obj2);
        } else {
            if (obj1 == null && obj2 != null)
            {
                return false; // first object is null
            } else if (obj1 != null && obj2 == null) {
                return false; // second object is null
            } else {
                return true; // Both objects have to be null
            }
        }
    }

    public RoutePutPropertyChangeMessage()
    {
        super();
        this.setType(RoutePutMessage.TYPE_PROPERTY_CHANGE);
    }

    /*
        So the goal here is to go through every property update in the packet and distribute them to the appropriate
        channels. The channel that the packet was received on should be assumed to have already processed it.

    */
    public void processUpdates(RoutePutSession receivingSession)
    {
        List<Object> updates = JSONTools.listJSONArray(this.getRoutePutMeta().optJSONArray("updates"));
        final ArrayList<RoutePutChannel> channelsInvolved = new ArrayList<RoutePutChannel>();
        // Lets look at each property update in the payload.
        updates.forEach((update) -> {
            try
            {
                if (update instanceof JSONObject)
                {
                    // LETS HANDLE EACH UPDATE IN THE PACKET!
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

                        // If a receiving session was passed, we should check if there are any updates specific to it
                        if (receivingSession != null)
                        {
                            if (receivingSession.getConnectionId().equals(objectId))
                            {
                                // OK this update belongs to the session that sent the packet
                                JSONObject rsProp = receivingSession.getProperties();

                                // Lock the store so read-of-old and write-of-new are atomic; this keeps the
                                // old->new chain consistent no matter how many threads produce updates.
                                synchronized (rsProp)
                                {
                                    Object currentValue = rsProp.opt(key);
                                    if (!nullSafeCompare(newValue, currentValue))
                                    {
                                        // Trust our stored value for old, not whatever the sender guessed.
                                        if (currentValue == null)
                                            joUpdate.remove("old");
                                        else
                                            joUpdate.put("old", currentValue);
                                        // OK the new value is different then the current, lets fire that change...
                                        receivingSession.firePropertyChange(key, currentValue, newValue);
                                        // Find every channel this session is a member of and broadcast its update
                                        Collection<RoutePutChannel> rpcc = RoutePutChannel.channelsWithMember(receivingSession);
                                        rpcc.forEach((channel) -> {
                                            if (!channelsInvolved.contains(channel))
                                                channelsInvolved.add(channel);
                                        });
                                        handled = true;
                                    }
                                }
                            }
                        }
                        
                        // Ok no receiving session was passed or there were updates to remote sessions included.
                        if (!handled)
                        {
                            RoutePutRemoteSession rprs = RoutePutRemoteSession.findRemoteSession(objectId);
                            if (rprs != null)
                            {
                                JSONObject rsProp = rprs.getProperties();
                                synchronized (rsProp)
                                {
                                    Object currentValue = rsProp.opt(key);
                                    if (!nullSafeCompare(newValue, currentValue))
                                    {
                                        // Trust our stored value for old, not whatever the sender guessed.
                                        if (currentValue == null)
                                            joUpdate.remove("old");
                                        else
                                            joUpdate.put("old", currentValue);
                                        // OK the new value is different then the current, lets fire that change...
                                        rprs.firePropertyChange(key, currentValue, newValue);
                                        Collection<RoutePutChannel> rpcc = RoutePutChannel.channelsWithMember(rprs);
                                        rpcc.forEach((channel) -> {
                                            if (!channelsInvolved.contains(channel))
                                                channelsInvolved.add(channel);
                                        });
                                    }
                                }
                            }
                        }

                    }
                }
            } catch (Exception e) {
                RoutePutServer.logError(e);
            }
        });
        channelsInvolved.forEach((channel) -> {
            // Bump the receive counter for this channel before broadcasting the update.
            channel.bumpRx();

            // broadcast the update to the channel
            channel.broadcast(this.forChannel(channel));
        });
    }

    public RoutePutPropertyChangeMessage addUpdate(RoutePutChannel channel, String key, Object oldValue, Object newValue)
    {
        JSONObject update = new JSONObject();
        update.put("type", TYPE_CHANNEL);
        //update.put("ts", System.currentTimeMillis());
        update.put("id", channel.getName());
        update.put("key", key);
        if (oldValue != null)
            update.put("old", oldValue);
        update.put("new", newValue);
        return this.addUpdate(update);
    }

    public RoutePutPropertyChangeMessage addUpdate(RoutePutSession session, String key, Object oldValue, Object newValue)
    {
        JSONObject update = new JSONObject();
        update.put("type", TYPE_SESSION);
        //update.put("ts", System.currentTimeMillis());
        update.put("id", session.getConnectionId());
        update.put("key", key);
        if (oldValue != null)
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
