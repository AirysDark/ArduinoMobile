package com.airysdark.arduinomobile;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class GitHubCompiler {
    interface Callback {
        void onStatus(String message);
        void onSuccess(String hexText);
        void onError(Exception error);
    }

    private static final String API = "https://api.github.com/repos/AirysDark/ArduinoMobile";

    static void compile(String sketch, String fqbn, String token, Callback callback) {
        new Thread(() -> {
            try {
                if (token == null || token.trim().isEmpty()) {
                    throw new IllegalArgumentException("GitHub token is required to start the compile workflow.");
                }

                String requestId = UUID.randomUUID().toString().substring(0, 8);
                callback.onStatus("Starting GitHub compile " + requestId + "...");

                JSONObject inputs = new JSONObject();
                inputs.put("request_id", requestId);
                inputs.put("fqbn", fqbn);
                inputs.put("sketch_b64", Base64.encodeToString(sketch.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));

                JSONObject body = new JSONObject();
                body.put("ref", "main");
                body.put("inputs", inputs);

                request("POST", API + "/actions/workflows/compile-sketch.yml/dispatches", token,
                        body.toString().getBytes(StandardCharsets.UTF_8));

                long runId = waitForRun(requestId, token, callback);
                waitForCompletion(runId, token, callback);
                byte[] zip = downloadArtifact(runId, requestId, token, callback);
                String hex = extractHex(zip);
                callback.onSuccess(hex);
            } catch (Exception e) {
                callback.onError(e);
            }
        }, "github-compiler").start();
    }

    private static long waitForRun(String requestId, String token, Callback callback) throws Exception {
        for (int i = 0; i < 40; i++) {
            JSONObject root = new JSONObject(new String(request("GET",
                    API + "/actions/workflows/compile-sketch.yml/runs?event=workflow_dispatch&per_page=20",
                    token, null), StandardCharsets.UTF_8));
            JSONArray runs = root.getJSONArray("workflow_runs");
            for (int j = 0; j < runs.length(); j++) {
                JSONObject run = runs.getJSONObject(j);
                String title = run.optString("display_title", "");
                if (title.contains(requestId)) {
                    callback.onStatus("Compile job found. Waiting for Arduino CLI...");
                    return run.getLong("id");
                }
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Timed out waiting for the GitHub workflow run to appear.");
    }

    private static void waitForCompletion(long runId, String token, Callback callback) throws Exception {
        for (int i = 0; i < 120; i++) {
            JSONObject run = new JSONObject(new String(request("GET", API + "/actions/runs/" + runId,
                    token, null), StandardCharsets.UTF_8));
            String status = run.optString("status");
            String conclusion = run.optString("conclusion");
            callback.onStatus("Compile status: " + status + (conclusion.isEmpty() ? "" : " / " + conclusion));
            if ("completed".equals(status)) {
                if (!"success".equals(conclusion)) {
                    throw new IllegalStateException("Arduino compile workflow finished with: " + conclusion);
                }
                return;
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("Timed out waiting for Arduino compilation.");
    }

    private static byte[] downloadArtifact(long runId, String requestId, String token, Callback callback) throws Exception {
        JSONObject root = new JSONObject(new String(request("GET", API + "/actions/runs/" + runId + "/artifacts",
                token, null), StandardCharsets.UTF_8));
        JSONArray artifacts = root.getJSONArray("artifacts");
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.getJSONObject(i);
            if (("firmware-" + requestId).equals(artifact.getString("name"))) {
                callback.onStatus("Downloading compiled firmware...");
                return request("GET", artifact.getString("archive_download_url"), token, null);
            }
        }
        throw new IllegalStateException("Compiled firmware artifact was not found.");
    }

    private static String extractHex(byte[] zipBytes) throws Exception {
        String fallback = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (!entry.isDirectory() && name.endsWith(".hex")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zin.read(buffer)) != -1) out.write(buffer, 0, count);
                    String text = out.toString(StandardCharsets.UTF_8.name());
                    if (!name.contains("with_bootloader")) return text;
                    fallback = text;
                }
            }
        }
        if (fallback != null) return fallback;
        throw new IllegalStateException("The build artifact did not contain a .hex firmware file.");
    }

    private static byte[] request(String method, String urlString, String token, byte[] body) throws Exception {
        URL url = new URL(urlString);
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "ArduinoMobile/0.1");
            if ("api.github.com".equalsIgnoreCase(url.getHost()) && token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.getOutputStream().write(body);
            }

            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IllegalStateException("Redirect without Location header.");
                url = new URL(location);
                method = "GET";
                body = null;
                continue;
            }

            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            byte[] response = out.toByteArray();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("GitHub HTTP " + code + ": " + new String(response, StandardCharsets.UTF_8));
            }
            return response;
        }
        throw new IllegalStateException("Too many HTTP redirects.");
    }

    private GitHubCompiler() {}
}
