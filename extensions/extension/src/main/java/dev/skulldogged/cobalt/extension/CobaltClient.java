package dev.skulldogged.cobalt.extension;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CobaltClient {
    private CobaltClient() {
    }

    static CobaltResponse request(String sourceUrl) throws Exception {
        JSONObject requestJson = new JSONObject()
                .put("url", sourceUrl)
                .put("downloadMode", "auto")
                .put("videoQuality", CobaltSettings.videoQuality())
                .put("youtubeVideoCodec", CobaltSettings.videoCodec())
                .put("youtubeVideoContainer", "mp4")
                .put("filenameStyle", CobaltSettings.filenameStyle())
                .put("localProcessing", "preferred")
                .put("youtubeBetterAudio", CobaltSettings.betterYouTubeAudio())
                .put("allowH265", true)
                .put("convertGif", true);

        HttpURLConnection connection =
                (HttpURLConnection) new URL(CobaltSettings.apiUrl()).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(90_000);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Cobalt-Morphe/0.1");
        String apiKey = CobaltSettings.apiKey();
        if (!apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Api-Key " + apiKey);
        }

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

            return CobaltResponse.direct(
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
            String type = json.optString("type");
            if (!"merge".equals(type)) {
                throw new CobaltException(
                        "unsupported cobalt local-processing type '" + type + "'"
                );
            }

            JSONObject output = json.optJSONObject("output");
            String filename = output == null
                    ? ""
                    : output.optString("filename");
            String mimeType = output == null
                    ? ""
                    : output.optString("type");
            JSONArray tunnelArray = json.optJSONArray("tunnel");

            if (!"video/mp4".equalsIgnoreCase(mimeType)
                    || filename.isEmpty()
                    || tunnelArray == null
                    || tunnelArray.length() != 2) {
                throw new CobaltException("invalid cobalt merge response");
            }

            List<String> tunnels = new ArrayList<>(2);
            for (int i = 0; i < tunnelArray.length(); i++) {
                String tunnel = tunnelArray.optString(i);
                if (tunnel.isEmpty()) {
                    throw new CobaltException("cobalt merge response contained an empty tunnel");
                }
                tunnels.add(tunnel);
            }
            return CobaltResponse.merge(tunnels, filename);
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
