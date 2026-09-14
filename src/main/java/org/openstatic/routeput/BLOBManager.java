package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.MimeTypes;
import org.json.JSONObject;

public class BLOBManager 
{
    private static HashMap<String, StringBuffer> blobStorage;
    private static HashMap<String, PendingBlobSend> pendingSends = new HashMap<String, PendingBlobSend>();
    // Futures awaiting completion of a blob fetch we initiated, keyed by request msgId.
    private static HashMap<String, PendingFetch> pendingFetches = new HashMap<String, PendingFetch>();
    private static File blobRoot;
    public static JSONObject settings = new JSONObject();
    // True when the current init came from initClient() (temp dir); a later explicit
    // init(settings) with non-null settings will replace it. Once a non-provisional
    // init has run, subsequent init() calls are ignored.
    private static boolean provisional = false;
    // Fires timeouts for stalled blob transfers so pending futures can never orphan.
    private static final java.util.concurrent.ScheduledExecutorService scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor((r) -> {
            Thread t = new Thread(r, "BLOBManager-timeout");
            t.setDaemon(true);
            return t;
        });

    private static class PendingBlobSend
    {
        RoutePutSession session;
        String name;
        RoutePutChannel channel;
        StringBuffer sb;
        RoutePutMessage request;
        CompletableFuture<Void> future;
        java.util.concurrent.ScheduledFuture<?> timeout;
    }

    // A requestBlob() future paired with the session it rode out on so a disconnect
    // (or a stalled stream) can fail it instead of leaving the caller blocked forever.
    private static class PendingFetch
    {
        RoutePutSession session;
        CompletableFuture<BLOBFile> future;
        java.util.concurrent.ScheduledFuture<?> timeout;
    }

    // Schedule a stall timeout for a pending transfer; returns null when disabled
    // (blobTransferTimeout <= 0).
    private static java.util.concurrent.ScheduledFuture<?> scheduleTransferTimeout(Runnable r)
    {
        long secs = BLOBManager.settings.optLong("blobTransferTimeout", 60L);
        if (secs <= 0) return null;
        return BLOBManager.scheduler.schedule(r, secs, java.util.concurrent.TimeUnit.SECONDS);
    }

    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes)
    {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++)
        {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static String md5OfBytes(byte[] bytes)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return bytesToHex(md.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    // Decode the base64 payload from a data URI StringBuffer and return raw bytes.
    private static byte[] decodeDataUri(StringBuffer sb)
    {
        try
        {
            int comma = sb.indexOf(",");
            if (comma < 0) return null;
            return java.util.Base64.getDecoder().decode(sb.substring(comma + 1));
        } catch (Exception e) {
            return null;
        }
    }

    public static File getBlobRoot()
    {
        return BLOBManager.blobRoot;
    }

    // True once a caller has explicitly opted in to blob storage by calling
    // init(settings) with a non-null settings object.
    public static boolean isInitialized()
    {
        return BLOBManager.blobRoot != null;
    }

    public static void init(JSONObject settings)
    {
        if (settings != null && (!isInitialized() || provisional))
        {
            BLOBManager.settings = settings;
            BLOBManager.blobRoot = new File(BLOBManager.settings.optString("blobStorageRoot", "./blob/"));
            if (!BLOBManager.blobRoot.exists())
            {
                BLOBManager.blobRoot.mkdir();
            }
            // 30 days default; caller can override with "blobStorageTimeout" in seconds.
            long timeoutSecs = BLOBManager.settings.optLong("blobStorageTimeout", 30L * 24L * 60L * 60L);
            sweepStaleBlobs(BLOBManager.blobRoot, timeoutSecs);
            provisional = false;
        }
        if (BLOBManager.blobStorage == null)
        {
            BLOBManager.blobStorage = new HashMap<String, StringBuffer>();
        }
    }

    // Bring up BLOBManager for a standalone RoutePutClient with a JVM temp directory
    // that is recursively deleted on shutdown. No-op when a previous init already ran;
    // an explicit init(settings) after this replaces the provisional temp root.
    public static void initClient()
    {
        if (isInitialized()) return;
        try
        {
            java.nio.file.Path tempPath = java.nio.file.Files.createTempDirectory("routeput-blob-");
            final File temp = tempPath.toFile();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursive(temp)));
            BLOBManager.settings = new JSONObject();
            BLOBManager.settings.put("blobStorageRoot", temp.getAbsolutePath());
            BLOBManager.blobRoot = temp;
            BLOBManager.provisional = true;
            if (BLOBManager.blobStorage == null)
            {
                BLOBManager.blobStorage = new HashMap<String, StringBuffer>();
            }
        } catch (Exception e) {
            RoutePutServer.logError(e);
        }
    }

    // Best-effort recursive delete used by the client temp-dir shutdown hook.
    private static void deleteRecursive(File f)
    {
        if (f == null || !f.exists()) return;
        if (f.isDirectory())
        {
            File[] children = f.listFiles();
            if (children != null)
            {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    // Delete any regular files under root whose lastModified is older than timeoutSecs,
    // then drop any subdirectories that end up empty. The root itself is preserved.
    private static void sweepStaleBlobs(File root, long timeoutSecs)
    {
        if (timeoutSecs <= 0 || root == null || !root.exists()) return;
        long cutoff = System.currentTimeMillis() - (timeoutSecs * 1000L);
        File[] entries = root.listFiles();
        if (entries == null) return;
        for (File f : entries)
        {
            if (f.isDirectory())
            {
                sweepStaleBlobs(f, timeoutSecs);
                String[] remaining = f.list();
                if (remaining != null && remaining.length == 0)
                {
                    try
                    {
                        if (f.delete())
                        {
                            RoutePutServer.logIt("BLOBManager removed empty blob folder: " + f.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        RoutePutServer.logError(e);
                    }
                }
            }
            else if (f.isFile() && f.lastModified() < cutoff)
            {
                try
                {
                    if (f.delete())
                    {
                        RoutePutServer.logIt("BLOBManager expired blob: " + f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    RoutePutServer.logError(e);
                }
            }
        }
    }

    public static void handleBlobData(RoutePutSession session, RoutePutMessage jo)
    {
        BLOBManager.init(null);
        // Client libraries that never opted in to blob storage should ignore chunk data.
        if (!BLOBManager.isInitialized()) return;
        JSONObject rpm = jo.getRoutePutMeta();

        // Non-chunk TYPE_BLOB messages (server ack for cached-remote or exists=false)
        // may reference a requestBlob() we initiated — resolve/reject the pending future.
        if (!jo.hasMetaField("i") && rpm.has("ref"))
        {
            String ref = rpm.optString("ref", null);
            boolean exists = rpm.optBoolean("exists", true);
            if (!exists)
            {
                completePendingFetch(ref, null, new java.util.NoSuchElementException("blob does not exist"));
                return;
            }
            if (rpm.optBoolean("cached", false))
            {
                String nm = rpm.optString("name", "");
                completePendingFetch(ref, resolveBlob(jo.getRoutePutChannel(), nm), null);
                return;
            }
        }

        // Only chunk data flows through TYPE_BLOB now; the have/need negotiation lives on
        // request/response messages (see handleBlobCheckRequest / handleBlobCheckResponse).
        if (jo.hasMetaField("i") && jo.hasMetaField("of") && jo.hasMetaField("data") && jo.hasMetaField("name"))
        {
            int i = rpm.optInt("i", 0);
            int of = rpm.optInt("of", 0);
            String name = rpm.optString("name", "");
            // Chunks with channel routing and no explicit target get relayed to the
            // other members in flight so we don't re-negotiate per recipient.
            if (jo.hasChannel() && !jo.hasTargetId())
            {
                jo.getRoutePutChannel().broadcast(jo);
            }
            // Key the reassembly buffer by channel + name so concurrent transfers of the
            // same file (or the same name across channels) never clobber each other.
            String channelKey = jo.hasChannel() ? jo.getChannel() : "";
            String storeKey = channelKey + ":" + name;
            StringBuffer sb;
            if (i == 1)
            {
                sb = new StringBuffer();
                BLOBManager.blobStorage.put(storeKey, sb);
            } else {
                sb = BLOBManager.blobStorage.get(storeKey);
            }
            // A missing buffer means we never saw chunk 1 (dropped/mis-routed); reject any
            // fetch waiting on it instead of NPEing and orphaning the future.
            if (sb == null)
            {
                if (rpm.has("ref"))
                {
                    completePendingFetch(rpm.optString("ref", null), null, new IllegalStateException("missing first chunk for blob: " + name));
                }
                return;
            }
            sb.append(rpm.optString("data",""));
            if (i == of)
            {
                RoutePutChannel channel = jo.getRoutePutChannel();
                File blobFolder = (channel != null) ? channel.getBlobFolder() : null;
                String channelName = (channel != null) ? channel.getName() : "(none)";
                RoutePutServer.log(RoutePutMessage.TYPE_LOG_INFO,"BLOB received: " + name + " Channel: " + channelName + " Client: " + session.getConnectionId());
                BLOBManager.blobStorage.remove(storeKey);
                BLOBFile blobFile = null;
                if (blobFolder != null)
                {
                    blobFile = new BLOBFile(blobFolder, channel.getName(), name);
                    BLOBManager.saveBase64Blob(blobFile, sb);
                    // Acknowledge blob sent
                    RoutePutMessage resp = new RoutePutMessage();
                    resp.setType(RoutePutMessage.TYPE_BLOB);
                    resp.mergeRouteputMeta(blobFile.toJSONObject());
                    resp.setRef(jo);
                    if (jo.hasChannel())
                    {
                        resp.setChannel(jo.getRoutePutChannel());
                    }
                    session.send(resp);
                }
                // Always settle a fetch we started, even when there was no folder to save
                // into, so the caller's future can never hang on the final chunk.
                if (rpm.has("ref"))
                {
                    String ref = rpm.optString("ref", null);
                    if (blobFile != null)
                        completePendingFetch(ref, blobFile, null);
                    else
                        completePendingFetch(ref, null, new IllegalStateException("blob received but no storage folder for channel: " + channelName));
                }
            }
        }
    }

    // Handle a "do you have this blob?" request from a peer. The server looks up its
    // own storage and replies with state=have or state=need.
    public static void handleBlobCheckRequest(RoutePutSession session, RoutePutMessage request)
    {
        BLOBManager.init(null);
        JSONObject rpm = request.getRoutePutMeta();
        String name = rpm.optString("name", "");
        String remoteMd5 = rpm.optString("md5", "");
        long remoteSize = rpm.optLong("size", -1);
        RoutePutChannel channel = request.getRoutePutChannel();
        // Client libraries with no blob storage opt-in reply "have" so the remote skips
        // pushing chunks that would just be discarded.
        if (!BLOBManager.isInitialized())
        {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setChannel(request.getChannel());
            resp.setResponse("blobCheck", request);
            resp.setMetaField("name", name);
            resp.setMetaField("md5", remoteMd5);
            resp.setMetaField("size", remoteSize);
            resp.setMetaField("state", "have");
            session.send(resp);
            return;
        }

        boolean have = false;
        File blobFolder = (channel != null) ? channel.getBlobFolder() : null;
        if (blobFolder != null && blobFolder.exists())
        {
            BLOBFile bf = new BLOBFile(blobFolder, channel.getName(), name);
            if (bf.exists() && bf.length() == remoteSize)
            {
                String localMd5 = bf.getMD5();
                if (localMd5 != null && localMd5.equalsIgnoreCase(remoteMd5))
                {
                    have = true;
                }
            }
        }

        // Always answer — a missing response would strand the sender's pendingSends entry
        // and, in turn, the fetcher waiting on those chunks.
        RoutePutMessage resp = new RoutePutMessage();
        resp.setResponse("blobCheck", request);
        resp.setMetaField("name", name);
        resp.setMetaField("md5", remoteMd5);
        resp.setMetaField("size", remoteSize);
        resp.setMetaField("state", have ? "have" : "need");
        session.send(resp);
    }

    // Handle the response to a blobCheck request we sent earlier. Either fires the
    // pending chunk transmission (state=need) or synthesizes a completion ack (state=have).
    public static void handleBlobCheckResponse(RoutePutSession session, RoutePutMessage jo)
    {
        JSONObject rpm = jo.getRoutePutMeta();
        String ref = rpm.optString("ref", null);
        if (ref == null) return;
        PendingBlobSend pending;
        synchronized (BLOBManager.pendingSends)
        {
            pending = BLOBManager.pendingSends.remove(ref);
        }
        if (pending == null) return;
        if (pending.timeout != null) pending.timeout.cancel(false);

        String state = rpm.optString("state", "need");
        if ("have".equals(state))
        {
            if (pending.request != null)
            {
                RoutePutMessage ack = new RoutePutMessage();
                ack.setType(RoutePutMessage.TYPE_BLOB);
                ack.setRef(pending.request);
                ack.setMetaField("name", pending.name);
                ack.setMetaField("exists", true);
                ack.setMetaField("cached", true);
                if (pending.request.hasChannel())
                {
                    ack.setChannel(pending.request.getRoutePutChannel());
                }
                pending.session.send(ack);
            }
            if (pending.future != null) pending.future.complete(null);
        }
        else
        {
            sendBlobChunks(pending.session, pending.name, pending.channel, pending.sb, pending.request, pending.future);
        }
    }

    public static BLOBFile resolveBlob(RoutePutChannel channel, String name)
    {
        File blobFolder = BLOBManager.blobRoot;
        if (channel != null)
        {
            blobFolder = new File(BLOBManager.blobRoot, channel.getName());
            if (!blobFolder.exists())
            {
                blobFolder.mkdir();
            }
            BLOBFile blobFile = new BLOBFile(blobFolder, channel.getName(), name);
            return blobFile;
        }
        return null;
    }

    public static boolean blobExists(RoutePutChannel channel, String name)
    {
        File blobFile = resolveBlob(channel, name);
        if (blobFile != null)
        {
            return blobFile.exists();
        }
        return false;
    }

    public static CompletableFuture<BLOBFile> getBlob(RoutePutSession session, RoutePutChannel channel, String name)
    {
        BLOBFile blobFile = resolveBlob(channel, name);
        CompletableFuture<BLOBFile> future = new CompletableFuture<BLOBFile>();
        if (blobFile != null)
        {
            if (blobFile.exists())
            {
                future.complete(blobFile);
                return future;
            } else {
                return BLOBManager.requestBlob(session, channel, name);
            }
        } else {
            future.completeExceptionally(new IllegalArgumentException("blobFile is null"));
            return future;
        }
    }

    // Ask the given session (typically a RoutePutClient's connection to the server) to
    // send us a blob. Mirrors routeput.js `channel.getBlob(name)` semantics: emits a
    // `type:request, request:"blob"` and returns a future that completes when the last
    // chunk lands, or when the remote reports the blob as cached / missing.
    public static CompletableFuture<BLOBFile> requestBlob(RoutePutSession session, RoutePutChannel channel, String name)
    {
        CompletableFuture<BLOBFile> future = new CompletableFuture<BLOBFile>();
        if (!BLOBManager.isInitialized())
        {
            future.completeExceptionally(new IllegalStateException("BLOBManager not initialized; call BLOBManager.init(settings) before requestBlob()"));
            return future;
        }
        if (session == null)
        {
            future.completeExceptionally(new IllegalArgumentException("session is null"));
            return future;
        }
        RoutePutMessage req = new RoutePutMessage();
        req.setRequest("blob");
        req.setMetaField("name", name);
        if (channel != null) req.setChannel(channel);
        final String msgId = req.getMessageId();
        final PendingFetch pf = new PendingFetch();
        pf.session = session;
        pf.future = future;
        synchronized (BLOBManager.pendingFetches)
        {
            BLOBManager.pendingFetches.put(msgId, pf);
        }
        pf.timeout = scheduleTransferTimeout(() ->
            completePendingFetch(msgId, null, new java.util.concurrent.TimeoutException("blob request timed out: " + name)));
        session.send(req);
        return future;
    }

    // Resolve or reject a pending requestBlob() future keyed by the original msgId.
    private static void completePendingFetch(String ref, BLOBFile file, Throwable error)
    {
        if (ref == null) return;
        PendingFetch pf;
        synchronized (BLOBManager.pendingFetches)
        {
            pf = BLOBManager.pendingFetches.remove(ref);
        }
        if (pf == null) return;
        if (pf.timeout != null) pf.timeout.cancel(false);
        if (error != null) pf.future.completeExceptionally(error);
        else pf.future.complete(file);
    }

    // Fail every in-flight fetch and send tied to a session that just went away so no
    // caller is left blocked on a stream that can never resume.
    public static void failPendingTransfersForSession(RoutePutSession session, Throwable error)
    {
        if (session == null) return;
        Throwable cause = (error != null) ? error : new java.io.IOException("connection closed");
        java.util.ArrayList<PendingFetch> fetches = new java.util.ArrayList<PendingFetch>();
        synchronized (BLOBManager.pendingFetches)
        {
            java.util.Iterator<java.util.Map.Entry<String, PendingFetch>> it = BLOBManager.pendingFetches.entrySet().iterator();
            while (it.hasNext())
            {
                java.util.Map.Entry<String, PendingFetch> e = it.next();
                if (e.getValue().session == session)
                {
                    fetches.add(e.getValue());
                    it.remove();
                }
            }
        }
        for (PendingFetch pf : fetches)
        {
            if (pf.timeout != null) pf.timeout.cancel(false);
            pf.future.completeExceptionally(cause);
        }

        java.util.ArrayList<PendingBlobSend> sends = new java.util.ArrayList<PendingBlobSend>();
        synchronized (BLOBManager.pendingSends)
        {
            java.util.Iterator<java.util.Map.Entry<String, PendingBlobSend>> it = BLOBManager.pendingSends.entrySet().iterator();
            while (it.hasNext())
            {
                java.util.Map.Entry<String, PendingBlobSend> e = it.next();
                if (e.getValue().session == session)
                {
                    sends.add(e.getValue());
                    it.remove();
                }
            }
        }
        for (PendingBlobSend ps : sends)
        {
            if (ps.timeout != null) ps.timeout.cancel(false);
            if (ps.future != null) ps.future.completeExceptionally(cause);
        }
    }

    public static void fetchBlob(RoutePutSession session, RoutePutMessage request)
    {
        JSONObject rpm = request.getRoutePutMeta();
        String name = rpm.optString("name", "");
        RoutePutChannel channel = request.getRoutePutChannel();
        BLOBFile blobFile = resolveBlob(channel, name);
        if (blobFile != null)
        {
            if (blobFile.exists())
            {
                StringBuffer sb = blobFile.getBase64StringBuffer();
                transmitBlobChunks(session, name, channel, sb, request);
            } else {
                RoutePutMessage resp = new RoutePutMessage();
                resp.setType(RoutePutMessage.TYPE_BLOB);
                resp.setRef(request);
                resp.setMetaField("name", name);
                resp.setChannel(channel);
                resp.setMetaField("exists", false);
                session.send(resp);
                RoutePutServer.log(RoutePutMessage.TYPE_LOG_ERROR,"BLOB not found: " + name + " in Channel: " + channel.getName() + " Client: " + session.getConnectionId());
            }
        } else {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType(RoutePutMessage.TYPE_BLOB);
            resp.setRef(request);
            resp.setMetaField("name", name);
            resp.setChannel(channel);
            resp.setMetaField("exists", false);
            session.send(resp);
            RoutePutServer.log(RoutePutMessage.TYPE_LOG_ERROR,"BLOB not found: " + name + " in Channel: " + channel.getName() + " Client: " + session.getConnectionId());
        }
    }


    public static CompletableFuture<Void> sendBlob(RoutePutSession session, RoutePutChannel channel, File file, RoutePutMessage request)
    {
        if (file == null || !file.exists())
        {
            CompletableFuture<Void> f = new CompletableFuture<Void>();
            f.completeExceptionally(new IllegalArgumentException("file is null or does not exist"));
            return f;
        }
        String name = file.getName();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file))
        {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        catch (IOException e)
        {
            CompletableFuture<Void> f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
        return sendBlob(session, name, channel, getContentTypeFor(name), baos.toByteArray(), request);
    }

    // Send a chunked blob to client from byte array
    public static CompletableFuture<Void> sendBlob(RoutePutSession session, String name, RoutePutChannel channel, String contentType, byte[] bytes)
    {
        return sendBlob(session, name, channel, contentType, bytes, null);
    }

    public static CompletableFuture<Void> sendBlob(RoutePutSession session, String name, RoutePutChannel channel, String contentType, byte[] bytes, RoutePutMessage request)
    {
        StringBuffer sb = new StringBuffer();
        sb.append("data:" + contentType + ";base64,");
        sb.append(java.util.Base64.getEncoder().encodeToString(bytes));
        return transmitBlobChunks(session, name, channel, sb, request);
    }
    
    // Transmit a blob to this session, first querying the remote to see if it already
    // has the file (matching name/size/md5). If so, chunks are skipped entirely.
    private static CompletableFuture<Void> transmitBlobChunks(final RoutePutSession session, final String name, final RoutePutChannel channel, final StringBuffer sb, final RoutePutMessage request)
    {
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        byte[] raw = decodeDataUri(sb);
        String md5 = (raw != null) ? md5OfBytes(raw) : null;
        long size = (raw != null) ? raw.length : sb.length();

        if (md5 == null)
        {
            // Cannot compute md5 — fall back to sending chunks directly.
            sendBlobChunks(session, name, channel, sb, request, future);
            return future;
        }

        RoutePutMessage query = new RoutePutMessage();
        query.setRequest("blobCheck");
        query.setMetaField("name", name);
        query.setMetaField("md5", md5);
        query.setMetaField("size", size);
        if (request != null && request.hasChannel())
        {
            query.setChannel(request.getRoutePutChannel());
        }

        PendingBlobSend pending = new PendingBlobSend();
        pending.session = session;
        pending.name = name;
        pending.channel = channel;
        pending.sb = sb;
        pending.request = request;
        pending.future = future;
        final String queryId = query.getMessageId();
        synchronized (BLOBManager.pendingSends)
        {
            BLOBManager.pendingSends.put(queryId, pending);
        }
        pending.timeout = scheduleTransferTimeout(() -> timeoutPendingSend(queryId, name));

        session.send(query);
        return future;
    }

    // Drop a blobCheck send that never got a reply so its future can't hang forever.
    private static void timeoutPendingSend(String ref, String name)
    {
        PendingBlobSend pending;
        synchronized (BLOBManager.pendingSends)
        {
            pending = BLOBManager.pendingSends.remove(ref);
        }
        if (pending == null) return;
        if (pending.future != null)
            pending.future.completeExceptionally(new java.util.concurrent.TimeoutException("blobCheck timed out: " + name));
    }

    // Actual chunk transmission — called after the remote replies state=need, or as a
    // fallback when md5 can't be computed. Completes `future` when the last chunk is sent.
    private static Thread sendBlobChunks(final RoutePutSession session, final String name, final RoutePutChannel channel, final StringBuffer sb, final RoutePutMessage request, final CompletableFuture<Void> future)
    {
        Thread x = new Thread(() -> {
            try
            {
                int size = sb.length();
                int chunkSize = 4096;
                int numChunks = (size + chunkSize - 1) / chunkSize;
                for (int i = 0; i < numChunks; i++)
                {
                    RoutePutMessage mm = new RoutePutMessage();
                    mm.setType("blob");
                    mm.setMetaField("name", name);
                    mm.setChannel(channel);
                    mm.setMetaField("i", i+1);
                    mm.setMetaField("of", numChunks);
                    if ((i + 1) == numChunks && request != null)
                    {
                        mm.setRef(request);
                    }
                    int start = i*chunkSize;
                    int end = start + chunkSize;
                    if (end > size)
                        end = size;
                    mm.setMetaField("data", sb.substring(start,end));
                    session.send(mm);
                }
                String channelName = (channel != null) ? channel.getName() : "(none)";
                RoutePutServer.log(RoutePutMessage.TYPE_LOG_INFO,"BLOB transmitted: " + name + " in channel: " + channelName + " Client: " + session.getConnectionId());
                if (future != null) future.complete(null);
            } catch (Exception t) {
                if (future != null) future.completeExceptionally(t);
                else RoutePutServer.logError(t);
            }
        });
        x.start();
        return x;
    }

    public static File saveBase64Blob(File file, StringBuffer sb)
    {
        try
        {
            byte[] fileData = java.util.Base64.getDecoder().decode(sb.substring(sb.indexOf(",") + 1));
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(fileData);
            fos.close();
            return file;
        } catch (Exception e) {
            //logIt(e);
        }
        return null;
    }
    
    public static StringBuffer loadBase64Blob(File file)
    {
        StringBuffer sb = new StringBuffer();
        try
        {
            String contentType = getContentTypeFor(file.getName());
            if (file.exists())
            {
                sb.append("data:" + contentType + ";base64,");
                FileInputStream fis = new FileInputStream(file);
                byte[] bFile = new byte[(int) file.length()];
                fis.read(bFile);
                fis.close();
                sb.append(java.util.Base64.getEncoder().encodeToString(bFile));
            }
        } catch (Exception e) {
            //logIt(e);
        }
        return sb;
    }

    /** Determine the content type of a local file */
    public static String getContentTypeFor(String filename)
    {
        String lc_file = filename.toLowerCase();
        if (lc_file.endsWith(".html") || lc_file.endsWith(".htm"))
        {
            return "text/html";
        } else if (lc_file.endsWith(".txt")) {
            return "text/plain";
        } else if (lc_file.endsWith(".css")) {
            return "text/css";
        } else if (lc_file.endsWith(".js")) {
            return "text/javascript";
        } else if (lc_file.endsWith(".jpg") || lc_file.endsWith(".jpe") || lc_file.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lc_file.endsWith(".gif")) {
            return "image/gif";
        } else if (lc_file.endsWith(".png")) {
            return "image/png";
        } else if (lc_file.endsWith(".bmp")) {
            return "image/x-ms-bmp";
        } else if (lc_file.endsWith(".mp3")) {
            return "audio/mpeg3";
        } else if (lc_file.endsWith(".zip")) {
            return "application/zip";
        } else if (lc_file.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lc_file.endsWith(".xml")) {
            return "text/xml";
        } else if (lc_file.endsWith(".mid") || lc_file.endsWith(".midi")) {
            return "audio/midi";
        } else if (lc_file.endsWith(".tar")) {
            return "application/x-tar";
        } else if (lc_file.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lc_file.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (lc_file.endsWith(".mp4")) {
            return "video/mp4";
        } else if (lc_file.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (lc_file.endsWith(".mov")) {
            return "video/quicktime";
        } else if (lc_file.endsWith(".wmv")) {
            return "video/x-ms-wmv";
        } else if (lc_file.endsWith(".3gp")) {
            return "video/3gpp";
        } else if (lc_file.endsWith(".flv")) {
            return "video/x-flv";
        } else if (lc_file.endsWith(".webm")) {
            return "video/webm";
        } else if (lc_file.endsWith(".ogm")) {
            return "video/ogg";
        } else if (lc_file.endsWith(".ogg")) {
            return "audio/ogg";
        } else if (lc_file.endsWith(".wav")) {
            return "audio/wav";
        } else if (lc_file.endsWith(".flac")) {
            return "audio/flac";
        } else if (lc_file.endsWith(".aac")) {
            return "audio/aac";
        } else if (lc_file.endsWith(".m4a")) {
            return "audio/mp4";
        } else {
            String result = MimeTypes.getDefaultMimeByExtension(filename);
            if ("".equals(result) || result == null)
                result = "application/octet-stream";
            return result;
        }
    }
}