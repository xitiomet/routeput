package org.openstatic.routeput;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;

import org.eclipse.jetty.http.MimeTypes;
import org.json.JSONObject;

public class BLOBManager 
{
    private static HashMap<String, StringBuffer> blobStorage;
    private static HashMap<String, PendingBlobSend> pendingSends = new HashMap<String, PendingBlobSend>();
    private static File blobRoot;
    public static JSONObject settings = new JSONObject();

    private static class PendingBlobSend
    {
        RoutePutSession session;
        String name;
        String context;
        StringBuffer sb;
        RoutePutMessage request;
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

    public static void init(JSONObject settings)
    {
        if (settings != null)
        {
            BLOBManager.settings = settings;
        }
        if (BLOBManager.blobRoot == null)
        {
            BLOBManager.blobRoot = new File(BLOBManager.settings.optString("blobStorageRoot", "./blob/"));
            if (!BLOBManager.blobRoot.exists())
            {
                BLOBManager.blobRoot.mkdir();
            }
        }
        if (BLOBManager.blobStorage == null)
        {
            BLOBManager.blobStorage = new HashMap<String, StringBuffer>();
        }
    }

    public static void handleBlobData(RoutePutSession session, RoutePutMessage jo)
    {
        BLOBManager.init(null);
        JSONObject rpm = jo.getRoutePutMeta();

        // Case A: response to a query we sent earlier (has state + ref)
        if (jo.hasMetaField("state") && jo.hasMetaField("ref"))
        {
            String ref = rpm.optString("ref", null);
            PendingBlobSend pending = null;
            synchronized (BLOBManager.pendingSends)
            {
                pending = BLOBManager.pendingSends.remove(ref);
            }
            if (pending != null)
            {
                String state = rpm.optString("state", "need");
                if ("have".equals(state))
                {
                    // Remote already has the file — skip chunks. If someone was awaiting a
                    // completion ack (fetchBlob), synthesize the same "exists=true" ack.
                    if (pending.request != null)
                    {
                        RoutePutMessage ack = new RoutePutMessage();
                        ack.setType(RoutePutMessage.TYPE_BLOB);
                        ack.setRef(pending.request);
                        ack.setMetaField("name", pending.name);
                        if (pending.context != null)
                        {
                            ack.setMetaField("context", pending.context);
                        }
                        ack.setMetaField("exists", true);
                        ack.setMetaField("cached", true);
                        if (pending.request.hasChannel())
                        {
                            ack.setChannel(pending.request.getRoutePutChannel());
                        }
                        pending.session.send(ack);
                    }
                }
                else
                {
                    sendBlobChunks(pending.session, pending.name, pending.context, pending.sb, pending.request);
                }
            }
            return;
        }

        // Case B: query from the remote asking "do you have this blob?"
        // Identified by md5 + name + size present and no chunk fields.
        if (jo.hasMetaField("md5") && jo.hasMetaField("name") && jo.hasMetaField("size")
            && !jo.hasMetaField("data") && !jo.hasMetaField("i") && !jo.hasMetaField("of")
            && !jo.hasMetaField("state"))
        {
            String context = rpm.optString("context", null);
            String name = rpm.optString("name", "");
            String remoteMd5 = rpm.optString("md5", "");
            long remoteSize = rpm.optLong("size", -1);

            File blobFolder = null;
            if (context == null && jo.getRoutePutChannel() != null)
            {
                RoutePutChannel chan = jo.getRoutePutChannel();
                blobFolder = chan.getBlobFolder();
                context = "channel." + chan.getName();
            } else if (context != null) {
                blobFolder = new File(BLOBManager.blobRoot, context);
                if (!blobFolder.exists())
                {
                    blobFolder.mkdir();
                }
            } else {
                blobFolder = BLOBManager.blobRoot;
            }

            boolean have = false;
            if (blobFolder != null && blobFolder.exists())
            {
                BLOBFile bf = new BLOBFile(blobFolder, context, name);
                if (bf.exists() && bf.length() == remoteSize)
                {
                    String localMd5 = bf.getMD5();
                    if (localMd5 != null && localMd5.equalsIgnoreCase(remoteMd5))
                    {
                        have = true;
                    }
                }
            }

            RoutePutMessage resp = new RoutePutMessage();
            resp.setType(RoutePutMessage.TYPE_BLOB);
            resp.setRef(jo);
            resp.setMetaField("name", name);
            resp.setMetaField("md5", remoteMd5);
            resp.setMetaField("size", remoteSize);
            if (context != null)
            {
                resp.setMetaField("context", context);
            }
            resp.setMetaField("state", have ? "have" : "need");
            if (jo.hasChannel())
            {
                resp.setChannel(jo.getRoutePutChannel());
            }
            session.send(resp);
            return;
        }

        // Case C: chunk data (existing behavior)
        if (jo.hasMetaField("i") && jo.hasMetaField("of") && jo.hasMetaField("data") && jo.hasMetaField("name"))
        {
            String context = rpm.optString("context", null);

            int i = rpm.optInt("i", 0);
            int of = rpm.optInt("of", 0);
            String name = rpm.optString("name", "");
            StringBuffer sb;
            if (i == 1)
            {
                sb = new StringBuffer();
                BLOBManager.blobStorage.put(name, sb);
            } else {
                sb = BLOBManager.blobStorage.get(name);
            }
            sb.append(rpm.optString("data",""));
            if (i == of)
            {
                File blobFolder = null;
                if (context == null && jo.getRoutePutChannel() != null)
                {
                    RoutePutChannel chan = jo.getRoutePutChannel();
                    blobFolder = chan.getBlobFolder();
                    context = "channel." + chan.getName();
                } else {
                    blobFolder = new File(BLOBManager.blobRoot, context);
                    if (!blobFolder.exists())
                    {
                        blobFolder.mkdir();
                    }
                }
                if (blobFolder != null)
                {
                    BLOBFile blobFile = new BLOBFile(blobFolder, context, name);
                    BLOBManager.saveBase64Blob(blobFile, sb);
                    BLOBManager.blobStorage.remove(name);
                    // Acknowledge blob sent
                    RoutePutMessage resp = new RoutePutMessage();
                    resp.setType(RoutePutMessage.TYPE_BLOB);
                    resp.mergeRouteputMeta(blobFile.toJSONObject());
                    resp.setRef(jo);
                    if (jo.hasChannel())
                    {
                        resp.setChannel(jo.getRoutePutChannel());
                        jo.getRoutePutChannel().onMessage(null, resp);
                    } else {
                        session.send(resp);
                    }
                }
            }
        }
    }

    public static BLOBFile resolveBlob(String context, String name)
    {
        File blobFolder = BLOBManager.blobRoot;
        if (context != null)
        {
            blobFolder = new File(BLOBManager.blobRoot, context);
            if (!blobFolder.exists())
            {
                blobFolder.mkdir();
            }
        }
        if (blobFolder != null)
        {
            BLOBFile blobFile = new BLOBFile(blobFolder, context, name);
            return blobFile;
        }
        return null;
    }

    public static boolean blobExists(String context, String name)
    {
        File blobFile = resolveBlob(context, name);
        if (blobFile != null)
        {
            return blobFile.exists();
        }
        return false;
    }

    public static void fetchBlob(RoutePutSession session, RoutePutMessage request)
    {
        JSONObject rpm = request.getRoutePutMeta();
        String name = rpm.optString("name", "");
        String context = rpm.optString("context");
        RoutePutChannel channel = request.getRoutePutChannel();
        BLOBFile blobFile = resolveBlob(context, name);
        if (blobFile != null)
        {
            if (blobFile.exists())
            {
                StringBuffer sb = blobFile.getBase64StringBuffer();
                transmitBlobChunks(session, name, context, sb, request);
            } else {
                RoutePutMessage resp = new RoutePutMessage();
                resp.setType(RoutePutMessage.TYPE_BLOB);
                resp.setRef(request);
                resp.setMetaField("name", name);
                resp.setChannel(channel);
                if (context != null)
                {
                    resp.setMetaField("context", context);
                }
                resp.setMetaField("exists", false);
                session.send(resp);
            }
        } else {
            RoutePutMessage resp = new RoutePutMessage();
            resp.setType(RoutePutMessage.TYPE_BLOB);
            resp.setRef(request);
            resp.setMetaField("name", name);
            resp.setChannel(channel);
            if (context != null)
            {
                resp.setMetaField("context", context);
            }
            resp.setMetaField("exists", false);
            session.send(resp);
        }
    }

    // Send a chunked blob to client from byte array
    public static void sendBlob(RoutePutSession session, String name, final String context, String contentType, byte[] bytes)
    {
        StringBuffer sb = new StringBuffer();
        sb.append("data:" + contentType + ";base64,");
        sb.append(java.util.Base64.getEncoder().encodeToString(bytes));
        transmitBlobChunks(session, name, context, sb, null);
    }
    
    // Transmit a blob to this session, first querying the remote to see if it already
    // has the file (matching name/size/md5). If so, chunks are skipped entirely.
    private static void transmitBlobChunks(final RoutePutSession session, final String name, final String context, final StringBuffer sb, final RoutePutMessage request)
    {
        byte[] raw = decodeDataUri(sb);
        String md5 = (raw != null) ? md5OfBytes(raw) : null;
        long size = (raw != null) ? raw.length : sb.length();

        if (md5 == null)
        {
            // Cannot compute md5 — fall back to sending chunks directly.
            sendBlobChunks(session, name, context, sb, request);
            return;
        }

        RoutePutMessage query = new RoutePutMessage();
        query.setType(RoutePutMessage.TYPE_BLOB);
        query.setMetaField("name", name);
        query.setMetaField("md5", md5);
        query.setMetaField("size", size);
        if (context != null)
        {
            query.setMetaField("context", context);
        }
        if (request != null && request.hasChannel())
        {
            query.setChannel(request.getRoutePutChannel());
        }

        PendingBlobSend pending = new PendingBlobSend();
        pending.session = session;
        pending.name = name;
        pending.context = context;
        pending.sb = sb;
        pending.request = request;
        synchronized (BLOBManager.pendingSends)
        {
            BLOBManager.pendingSends.put(query.getMessageId(), pending);
        }

        session.send(query);
    }

    // Actual chunk transmission — called after the remote replies state=need, or as a
    // fallback when md5 can't be computed.
    private static void sendBlobChunks(final RoutePutSession session, final String name, final String context, final StringBuffer sb, final RoutePutMessage request)
    {
        Thread x = new Thread(() -> {
            int size = sb.length();
            int chunkSize = 4096;
            int numChunks = (size + chunkSize - 1) / chunkSize;
            for (int i = 0; i < numChunks; i++)
            {
                RoutePutMessage mm = new RoutePutMessage();
                mm.setType("blob");
                mm.setMetaField("name", name);
                if (context != null)
                {
                    mm.setMetaField("context", context);
                }
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
        });
        x.start();
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
        } else {
            String result = MimeTypes.getDefaultMimeByExtension(filename);
            if ("".equals(result) || result == null)
                result = "application/octet-stream";
            return result;
        }
    }
}