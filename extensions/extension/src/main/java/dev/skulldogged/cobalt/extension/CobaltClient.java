package dev.skulldogged.cobalt.extension;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class CobaltClient {
    private static final String API_URL = "https://cobalt.skulldogged.dev/api/";

    private CobaltClient() {
    }

    static CobaltResponse request(String sourceUrl) throws Exception {
        JSONObject requestJson = new JSONObject()
                .put("url", sourceUrl)
                .put("downloadMode", "auto")
                .put("videoQuality", "1080")
                .put("youtubeVideoCodec", "h264")
                .put("youtubeVideoContainer", "mp4")
                .put("filenameStyle", "pretty")
                .put("localProcessing", "disabled");

        HttpURLConnection connection =
                (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(90_000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Cobalt-Morphe/0.1");

        byte[] body = requestJson.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readFully(stream);

            if (responseBody.isEmpty()) {
                throw new CobaltException("cobalt returned HTTP " + statusCode);
            }

            return parseResponse(statusCode, new JSONObject(responseBody));
        } finally {
            connection.disconnect();
        }
    }

    private static CobaltResponse parseResponse(int statusCode, JSONObject json)
            throws Exception {
        String status = json.optString("status");

        if ("tunnel".equals(status) || "redirect".equals(status)) {
            String url = json.optString("url");
            if (url.isEmpty()) {
                throw new CobaltException("cobalt response did not contain a download URL");
            }

            return new CobaltResponse(
                    url,
                    json.optString("filename", "cobalt-download.mp4")
            );
        }

        if ("error".equals(status)) {
            JSONObject error = json.optJSONObject("error");
            String code = error == null ? "unknown" : error.optString("code", "unknown");
            throw new CobaltException(code);
        }

        if ("picker".equals(status)) {
            throw new CobaltException("cobalt returned multiple items");
        }

        if ("local-processing".equals(status)) {
            throw new CobaltException("cobalt requested unsupported local processing");
        }

        throw new CobaltException(
                "unexpected cobalt response '" + status + "' (HTTP " + statusCode + ")"
        );
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
