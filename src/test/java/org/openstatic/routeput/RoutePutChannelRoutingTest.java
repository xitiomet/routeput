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
import org.openstatic.routeput.client.RoutePutClient;
import static org.junit.Assert.*;

public class RoutePutChannelRoutingTest
{
    private RoutePutChannel channel;
    private final List<RoutePutClient> clients = new ArrayList<RoutePutClient>();
    private final List<RoutePutRemoteSession> registeredRemotes = new ArrayList<RoutePutRemoteSession>();

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
        this.clients.forEach(RoutePutClient::shutdown);
        if (!this.registeredRemotes.isEmpty())
            RoutePutRemoteSession.getAllRemoteSessions().removeAll(this.registeredRemotes);
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
        assertTrue(message.hasMetaField("where"));
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
        assertTrue(delivery.hasMetaField("where"));
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

    @Test
    public void filteredBroadcastBatchesManyRemoteMatchesThroughUnmatchedRoot()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        for (int i = 0; i < 100; i++)
            remoteMember(parent);
        RecordingSession recipient = member(true);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertTrue(sender.received.isEmpty());
        assertForwardedCopy(parent, message);
        assertTargetedCopy(recipient, message);
        assertFalse(message.hasTargetId());
    }

    @Test
    public void matchingRootAndChildrenOnlyReceiveOneForwardedPacket()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(true);
        remoteMember(parent);
        remoteMember(parent);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertForwardedCopy(parent, message);
    }

    @Test
    public void filteredBroadcastForwardsOncePerDistinctRelay()
    {
        RecordingSession sender = member(true);
        RecordingSession first = member(false);
        RecordingSession second = member(false);
        remoteMember(first);
        remoteMember(first);
        remoteMember(second);
        remoteMember(second);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertForwardedCopy(first, message);
        assertForwardedCopy(second, message);
    }

    @Test
    public void unmatchedRemoteMembersDoNotCauseForwarding()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        RoutePutRemoteSession child = remoteMember(parent);
        child.getProperties().put("x", false);

        this.channel.onMessage(sender, filteredMessage(sender));

        assertTrue(parent.received.isEmpty());
    }

    @Test
    public void sourceLinkSiblingsStayTargetedWhileOtherLinksAreBatched()
    {
        RecordingSession sourceLink = member(false);
        RoutePutRemoteSession sender = remoteMember(sourceLink);
        RoutePutRemoteSession sibling = remoteMember(sourceLink);
        RoutePutRemoteSession secondSibling = remoteMember(sourceLink);
        RecordingSession otherLink = member(false);
        remoteMember(otherLink);
        remoteMember(otherLink);
        RoutePutMessage message = filteredMessage(sender);

        this.channel.onMessage(sender, message);

        assertEquals(2, sourceLink.received.size());
        Set<String> targets = new HashSet<String>();
        sourceLink.received.forEach((m) -> targets.add(m.getTargetId()));
        assertEquals(Set.of(sibling.getConnectionId(), secondSibling.getConnectionId()), targets);
        assertForwardedCopy(otherLink, message);
    }

    @Test
    public void filteredBlobStillForwardsThroughOptedOutRelayToEligibleChild()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        parent.sessionProperties.put("_noBlobs", true);
        remoteMember(parent);
        RoutePutMessage message = filteredMessage(sender);
        message.setType(RoutePutMessage.TYPE_BLOB);

        this.channel.broadcast(message);

        assertForwardedCopy(parent, message);
    }

    @Test
    public void optedOutRemoteBlobRecipientsDoNotCauseForwarding()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        remoteMember(parent).getProperties().put("_noBlobs", true);
        RoutePutMessage message = filteredMessage(sender);
        message.setType(RoutePutMessage.TYPE_BLOB);

        this.channel.broadcast(message);

        assertTrue(parent.received.isEmpty());
    }

    @Test
    public void unmatchedJavaRelayForwardsWithoutApplicationDelivery()
    {
        RoutePutClient client = client(false);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession first = member(true);
        RecordingSession second = member(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);

        client.handleWebSocketEvent(message);

        assertTargetedCopy(first, message);
        assertTargetedCopy(second, message);
        assertTrue(callbacks.isEmpty());
        assertFalse(message.hasTargetId());
    }

    @Test
    public void matchingJavaRelayDeliversOneTargetedCallbackAndTargetsLocalChildren()
    {
        RoutePutClient client = client(true);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession recipient = member(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);

        client.handleWebSocketEvent(message);

        assertTargetedCopy(recipient, message);
        assertEquals(1, callbacks.size());
        assertEquals(client.getConnectionId(), callbacks.get(0).getTargetId());
        assertTrue(callbacks.get(0).hasMetaField("where"));
        assertTrue(callbacks.get(0).containsHop(RoutePutChannel.getMasterConnectionId()));
        assertFalse(message.hasTargetId());
    }

    @Test
    public void targetedFilteredChildPacketDoesNotLeakToMatchingJavaRelayListener()
    {
        RoutePutClient client = client(true);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession recipient = member(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);
        message.setTarget(recipient);

        client.handleWebSocketEvent(message);

        assertTargetedCopy(recipient, message);
        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void explicitTargetToJavaClientStillOverridesWhere()
    {
        RoutePutClient client = client(false);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession otherMatch = member(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);
        message.setTarget(client);

        client.handleWebSocketEvent(message);

        assertTrue(otherMatch.received.isEmpty());
        assertEquals(1, callbacks.size());
        assertEquals(client.getConnectionId(), callbacks.get(0).getTargetId());
    }

    @Test
    public void ordinaryJavaClientCallbacksRemainUnfiltered()
    {
        RoutePutClient client = client(false);
        RoutePutRemoteSession source = registeredRemote(client);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);
        message.removeMetaField("where");

        client.handleWebSocketEvent(message);

        assertEquals(1, callbacks.size());
        assertFalse(callbacks.get(0).hasTargetId());
    }

    @Test
    public void javaRelayRebatchesForwardedWhereForNextLink()
    {
        RoutePutClient client = client(false);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession nextLink = member(false);
        remoteMember(nextLink);
        remoteMember(nextLink);
        RecordingSession local = member(true);
        RoutePutMessage message = filteredMessage(source);
        message.appendHop("previous-environment");

        client.handleWebSocketEvent(message);

        assertForwardedCopy(nextLink, message);
        assertTargetedCopy(local, message);
        assertTrue(nextLink.received.get(0).containsHop("previous-environment"));
        assertTrue(nextLink.received.get(0).containsHop(RoutePutChannel.getMasterConnectionId()));
    }

    @Test
    public void previouslyVisitedJavaRelayDoesNotReceiveAnotherApplicationCallback()
    {
        RoutePutClient client = client(true);
        RoutePutRemoteSession source = registeredRemote(client);
        RecordingSession recipient = member(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));
        RoutePutMessage message = filteredMessage(source);
        message.appendHop(client.getConnectionId());

        client.handleWebSocketEvent(message);

        assertTrue(callbacks.isEmpty());
        assertTargetedCopy(recipient, message);
    }

    @Test
    public void javaRelayDoesNotReceiveItsOwnFilteredBroadcast()
    {
        RoutePutClient client = client(true);
        List<RoutePutMessage> callbacks = new ArrayList<RoutePutMessage>();
        client.addMessageListener((s, m) -> callbacks.add(m));

        client.handleWebSocketEvent(filteredMessage(client));

        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void priorRelayHopRetainsTargetedDeliveryToUnvisitedRemoteChild()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        RoutePutRemoteSession child = remoteMember(parent);
        RoutePutMessage message = filteredMessage(sender);
        message.appendHop(parent.getConnectionId());

        this.channel.onMessage(sender, message);

        assertEquals(1, parent.received.size());
        assertEquals(child.getConnectionId(), parent.received.get(0).getTargetId());
        assertTrue(parent.received.get(0).hasMetaField("where"));
    }

    @Test
    public void previouslyVisitedRemoteChildDoesNotCauseForwarding()
    {
        RecordingSession sender = member(true);
        RecordingSession parent = member(false);
        RoutePutRemoteSession child = remoteMember(parent);
        RoutePutMessage message = filteredMessage(sender);
        message.appendHop(child.getConnectionId());

        this.channel.onMessage(sender, message);

        assertTrue(parent.received.isEmpty());
    }

    private RoutePutClient client(boolean matches)
    {
        RoutePutClient client = new RoutePutClient(this.channel, "ws://localhost:6144/");
        client.getProperties().put("x", matches);
        this.clients.add(client);
        this.channel.members.put(client.getConnectionId(), client);
        return client;
    }

    private RoutePutRemoteSession registeredRemote(RoutePutSession parent)
    {
        RoutePutMessage join = new RoutePutMessage();
        join.setChannel(this.channel);
        join.setSourceId(UUID.randomUUID().toString());
        join.setType(RoutePutMessage.TYPE_CONNECTION_STATUS);
        join.setMetaField("connected", true);
        join.setMetaField("properties", new JSONObject().put("x", true));
        RoutePutRemoteSession remote = RoutePutRemoteSession.handleRoutedMessage(parent, join);
        this.registeredRemotes.add(remote);
        return remote;
    }

    private void assertForwardedCopy(RecordingSession parent, RoutePutMessage original)
    {
        assertEquals(1, parent.received.size());
        RoutePutMessage delivery = parent.received.get(0);
        assertFalse(delivery.hasTargetId());
        assertTrue(delivery.getRoutePutMeta().getJSONObject("where")
                .similar(original.getRoutePutMeta().getJSONObject("where")));
        assertEquals(original.getSourceId(), delivery.getSourceId());
        assertEquals(original.getMessageId(), delivery.getMessageId());
        assertEquals("refresh", delivery.getString("command"));
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
        assertTrue(delivery.hasMetaField("where"));
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
