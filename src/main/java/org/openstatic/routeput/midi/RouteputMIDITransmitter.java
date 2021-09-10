package org.openstatic.routeput.midi;

import org.openstatic.routeput.*;

import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import org.json.*;

public class RouteputMIDITransmitter implements Transmitter, RoutePutMessageListener
{
    private Receiver receiver;
    private RoutePutChannel channel;
    private long clockPosition;
    /**
     * Creates a RouteputMIDITransmitter connected to the provided channel
     * @param channel
     */
    public RouteputMIDITransmitter(RoutePutChannel channel)
    {
        this.channel = channel;
        this.channel.addMessageListener(this);
        this.clockPosition = -1;
    }

    @Override
    public Receiver getReceiver()
    {
        return this.receiver;
    }

    @Override
    public void setReceiver(Receiver receiver)
    {
        this.receiver = receiver;
    }
    
    @Override
    public void close()
    {
        this.channel.removeMessageListener(this);
    }

    @Override
    public void onMessage(RoutePutSession session, RoutePutMessage message) {
        try
        {
            if (message.isType(RoutePutMessage.TYPE_MIDI))
            {
                JSONArray data = message.getRoutePutMeta().getJSONArray("data");
                final long timeStamp = message.getRoutePutMeta().optLong("ts", this.clockPosition);
                int data0 = data.optInt(0, 0);
                int data1 = data.optInt(1, 0);
                int data2 = data.optInt(2, 0);
                int command = data0 & 0xF0;
                int channel = data0 & 0x0F;
                final ShortMessage sm = new ShortMessage(command, channel, data1, data2);
                if (this.receiver != null)
                {
                    this.receiver.send(sm, timeStamp);
                }
            } else if (message.isType(RoutePutMessage.TYPE_PULSE)) {
                final long timeStamp = message.getRoutePutMeta().optLong("ts", -1);
                this.clockPosition = timeStamp;
                final ShortMessage sm = new ShortMessage(ShortMessage.TIMING_CLOCK);
                if (this.receiver != null)
                {
                    this.receiver.send(sm, timeStamp);
                }
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
        }
    }
}
