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

    public RouteputMIDITransmitter(RoutePutChannel channel)
    {
        this.channel = channel;
        this.channel.addMessageListener(this);
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

    }

    @Override
    public void onMessage(RoutePutSession session, RoutePutMessage message) {
        try
        {
            if (message.isType(RoutePutMessage.TYPE_MIDI))
            {
                JSONArray data = message.getRoutePutMeta().getJSONArray("data");
                final long timeStamp = message.getRoutePutMeta().optLong("ts", -1);
                int data0 = data.optInt(0, 0);
                int data1 = data.optInt(1, 0);
                int data2 = data.optInt(2, 0);
                int command = data0 & 0xF0;
                int channel = data0 & 0x0F;
                final ShortMessage sm = new ShortMessage(command, channel, data1, data2);
                this.receiver.send(sm, timeStamp);
            } else if (message.isType(RoutePutMessage.TYPE_PULSE)) {
                final long timeStamp = message.getRoutePutMeta().optLong("ts", 0);
                final ShortMessage sm = new ShortMessage(ShortMessage.TIMING_CLOCK);
                this.receiver.send(sm, timeStamp);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}
