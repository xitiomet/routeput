package org.openstatic.routeput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoutePutChannelRoutingTest
{
    private RoutePutChannel channel;

    @Before
    public void setUp()
    {
        this.channel = RoutePutChannel.getChannel("routing-test-" + UUID.randomUUID());
    }

    @After
    public void tearDown()
    {
        this.channel.setCollector(null);
        this.channel.members.clear();
    }

    @Test
    public void filteredBroadcastExcludesMatchingSenderAndTargetsOtherMatches()
    {
        RecordingSession sender = member(true);
        RecordingSession first = member(true);
        RecordingSession second = member(true);
        RecordingSession nonMatch = member(false);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTrue(nonMatch.received.isEmpty());
        assertTargetedCopy(first, message);
        assertTargetedCopy(second, message);
        assertFalse(message.hasMetaField("where"));
        assertFalse(message.hasTargetId());
    }

    @Test
    public void filteredBroadcastExcludesSenderEvenWhenLinkDoesNotContainItsOwnId()
    {
        RecordingSession sender = member(true);
        sender.containsOwnId = false;
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTargetedCopy(recipient, message);
    }

    @Test
    public void emptyFilterStillExcludesSender()
    {
        RecordingSession sender = member(true);
        RecordingSession recipient = member(false);
        RoutePutMessage message = filteredMessage(sender);
        message.setMetaField("where", new JSONObject());

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTargetedCopy(recipient, message);
    }

    @Test
    public void echoFlagDoesNotBypassFilteredSenderExclusion()
    {
        RecordingSession sender = member(true);
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);
        message.setMetaField("echo", true);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTargetedCopy(recipient, message);
        assertTrue(message.optMetaField("echo", false));
    }

    @Test
    public void filteredBroadcastExcludesRemoteSenderAndItsLinkButKeepsRemotePeers()
    {
        RecordingSession parent = member(true);
        RoutePutRemoteSession sender = remoteMember(parent);
        RoutePutRemoteSession sibling = remoteMember(parent);
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertTargetedCopy(recipient, message);
        assertEquals(1, parent.received.size());
        RoutePutMessage delivery = parent.received.get(0);
        assertEquals(sibling.getConnectionId(), delivery.getTargetId());
        assertEquals(sender.getConnectionId(), delivery.getSourceId());
        assertFalse(delivery.hasMetaField("where"));
    }

    @Test
    public void filteredBroadcastPreservesHopExclusion()
    {
        RecordingSession sender = member(true);
        RecordingSession priorHop = member(true);
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);
        message.appendHop(priorHop.getConnectionId());

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTrue(priorHop.received.isEmpty());
        assertTargetedCopy(recipient, message);
    }

    @Test
    public void filteredBlobBroadcastExcludesSenderAndBlobOptOut()
    {
        RecordingSession sender = member(true);
        RecordingSession noBlobs = member(true);
        noBlobs.sessionProperties.put("_noBlobs", true);
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);
        message.setType(RoutePutMessage.TYPE_BLOB);

        this.channel.broadcast(message);

        assertTrue(sender.received.isEmpty());
        assertTrue(noBlobs.received.isEmpty());
        assertTargetedCopy(recipient, message);
    }

    @Test
    public void explicitTargetStillTakesPrecedenceOverWhere()
    {
        RecordingSession sender = member(true);
        RecordingSession target = member(false);
        RecordingSession otherMatch = member(true);
        RoutePutMessage message = filteredMessage(sender);
        message.setTarget(target);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTrue(otherMatch.received.isEmpty());
        assertEquals(1, target.received.size());
        assertEquals(target.getConnectionId(), target.received.get(0).getTargetId());
        assertTrue(target.received.get(0).hasMetaField("where"));
    }

    @Test
    public void collectorFilteredBroadcastExcludesCollectorSender()
    {
        RecordingSession collector = member(true);
        RecordingSession recipient = member(true);
        this.channel.setCollector(collector);
        RoutePutMessage message = filteredMessage(collector);

        this.channel.onMessage(collector, message);

        assertTrue(collector.received.isEmpty());
        assertTargetedCopy(recipient, message);
        assertTrue(recipient.received.get(0).optMetaField("collectorBroadcast", false));
    }

    @Test
    public void nonCollectorFilteredPacketIsStillAbsorbed()
    {
        RecordingSession sender = member(true);
        RecordingSession collector = member(false);
        RecordingSession recipient = member(true);
        this.channel.setCollector(collector);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertTrue(recipient.received.isEmpty());
        assertEquals(1, collector.received.size());
        assertTrue(collector.received.get(0).optMetaField("collectorAbsorbed", false));
    }

    @Test
    public void ordinaryBroadcastStillExcludesSender()
    {
        RecordingSession sender = member(true);
        RecordingSession recipient = member(false);
        RoutePutMessage message = filteredMessage(sender);
        message.removeMetaField("where");

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertEquals(1, recipient.received.size());
        assertFalse(recipient.received.get(0).hasTargetId());
    }

    private RecordingSession member(boolean matches)
    {
        RecordingSession session = new RecordingSession(this.channel, matches);
        this.channel.members.put(session.getConnectionId(), session);
        return session;
    }

    private RoutePutRemoteSession remoteMember(RecordingSession parent)
    {
        String id = UUID.randomUUID().toString();
        RoutePutRemoteSession session = new RoutePutRemoteSession(parent, id);
        session.getProperties().put("x", true);
        parent.children.add(id);
        this.channel.members.put(id, session);
        return session;
    }

    private RoutePutMessage filteredMessage(RoutePutSession sender)
    {
        RoutePutMessage message = new RoutePutMessage();
        message.setChannel(this.channel);
        message.setSource(sender);
        message.setLogged(false);
        message.setMetaField("where", new JSONObject().put("x", true));
        message.put("command", "refresh");
        return message;
    }

    private void assertTargetedCopy(RecordingSession recipient, RoutePutMessage original)
    {
        assertEquals(1, recipient.received.size());
        RoutePutMessage delivery = recipient.received.get(0);
        assertEquals(recipient.getConnectionId(), delivery.getTargetId());
        assertEquals(original.getSourceId(), delivery.getSourceId());
        assertEquals(original.getMessageId(), delivery.getMessageId());
        assertEquals("refresh", delivery.getString("command"));
        assertFalse(delivery.hasMetaField("where"));
    }

    private static class RecordingSession extends RoutePutServerWebsocket
    {
        private final JSONObject sessionProperties;
        private final List<RoutePutMessage> received = Collections.synchronizedList(new ArrayList<RoutePutMessage>());
        private final Set<String> children = new HashSet<String>();
        private boolean containsOwnId = true;

        private RecordingSession(RoutePutChannel channel, boolean matches)
        {
            this.connectionId = UUID.randomUUID().toString();
            this.defaultChannel = channel;
            this.sessionProperties = new JSONObject().put("x", matches);
        }

        @Override
        public JSONObject getProperties()
        {
            return this.sessionProperties;
        }

        @Override
        public void send(RoutePutMessage message)
        {
            this.received.add(new RoutePutMessage(message));
        }

        @Override
        public boolean containsConnectionId(String id)
        {
            return (this.containsOwnId && this.connectionId.equals(id)) || this.children.contains(id);
        }
    }
}
