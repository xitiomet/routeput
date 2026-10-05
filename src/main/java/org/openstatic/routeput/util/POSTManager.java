package org.openstatic.routeput.util;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class POSTManager
{
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    private POSTManager()
    {
    }

    public static class Response
    {
        private final int statusCode;
        private final String contentType;
        private final Map<String, List<String>> headers;
        private final String body;

        public Response(int statusCode, String contentType, Map<String, List<String>> headers, String body)
        {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.headers = headers;
            this.body = body;
        }

        public boolean isSuccessful()
        {
            return this.statusCode >= 200 && this.statusCode < 300;
        }

        public int getStatusCode()
        {
            return this.statusCode;
        }

        public String getContentType()
        {
            return this.contentType;
        }

        public Map<String, List<String>> getHeaders()
        {
            return this.headers;
        }

        public String getBody()
        {
            return this.body;
        }

        public String toString()
        {
            return this.body;
        }
    }

    /** Queue a JSON POST, resolving the returned future with the response **/
    public static CompletableFuture<Response> queuePost(String url, JSONObject object)
    {
        return CompletableFuture.supplyAsync(() -> post(url, object), executor);
    }

    private static Response post(String url, JSONObject object)
    {
        try
        {
            URL url_object = new URL(url);
            HttpURLConnection con = (HttpURLConnection) url_object.openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(15000);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            byte[] payload = object.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = con.getOutputStream())
            {
                os.write(payload);
            }
            con.connect();
            int response_code = con.getResponseCode();
            InputStream is = (response_code >= 200 && response_code < 400) ? con.getInputStream() : con.getErrorStream();
            String body = readInputStreamToString(is);
            return new Response(response_code, con.getContentType(), con.getHeaderFields(), body);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            throw new RuntimeException(e);
        }
    }

    /** Read the contents of an InputStream into a String **/
    private static String readInputStreamToString(InputStream is)
    {
        if (is == null)
            return null;
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int inputByte;
            while ((inputByte = is.read()) > -1)
            {
                baos.write(inputByte);
            }
            is.close();
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("readInputStreamToString " + e.getMessage());
            e.printStackTrace(System.err);
            return null;
        }
    }
}