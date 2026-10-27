package com.automaforge.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Persisted public game data and ephemeral, server-only private card memory. */
final class GameStore {
    private final File data;
    private final SecureRandom rng = new SecureRandom();
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Map<String, JSONArray> privateCards = new HashMap<>();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    GameStore(Context context) {
        data = new File(context.getFilesDir(), "rooms");
        if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("Não foi possível criar pasta de salas");
    }

    static JSONObject obj(Object... kv) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try { result.put((String) kv[i], kv[i + 1]); } catch (JSONException e) { throw new IllegalArgumentException(e); }
        }
        return result;
    }

    static String now() { return Instant.now().toString(); }

    synchronized JSONObject create(String name) throws Exception {
        String code;
        do {
            StringBuilder b = new StringBuilder(8);
            for (int i = 0; i < 8; i++) b.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
            code = b.toString();
        } while (new File(data, code + ".json").exists());
        JSONObject room = obj("code", code, "game_name", clip(name.trim().isEmpty() ? "Jogo de tabuleiro" : name, 100),
            "created_at", now(), "revision", 0, "rules_text", "", "setup", "", "turn_flow", "",
            "actions", new JSONArray(), "uncertainties", new JSONArray(), "rule_pages", new JSONArray(),
            "rules_draft", JSONObject.NULL, "turn", 1, "phase", "Preparação", "variables", new JSONObject(),
            "board_notes", "", "board_observation", JSONObject.NULL, "board_version", 0,
            "proposal", JSONObject.NULL, "history", new JSONArray(), "last_announcement", JSONObject.NULL);
        cache.put(code, room);
        privateCards.put(code, new JSONArray());
        save(room, false);
        return publicRoom(room);
    }

    synchronized JSONObject get(String rawCode) throws Exception {
        String code = rawCode.toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-HJ-NP-Z2-9]{8}")) throw new ApiException(404, "Sala não encontrada.");
        JSONObject loaded = cache.get(code);
        if (loaded != null) return loaded;
        File file = new File(data, code + ".json");
        if (!file.isFile()) throw new ApiException(404, "Sala não encontrada.");
        loaded = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        cache.put(code, loaded);
        // Private memory intentionally does NOT survive app restart.
        privateCards.put(code, new JSONArray());
        return loaded;
    }

    synchronized JSONArray privateCards(String code) { return privateCards.computeIfAbsent(code, c -> new JSONArray()); }
    synchronized void clearPrivate(String code) { privateCards.put(code, new JSONArray()); }

    synchronized JSONObject publicRoom(JSONObject room) throws Exception {
        JSONObject safe = new JSONObject(room.toString());
        safe.remove("private_cards");
        safe.put("private_card_count", privateCards(safe.getString("code")).length());
        return safe;
    }

    synchronized void save(JSONObject room, boolean revision) throws Exception {
        if (revision) room.put("revision", room.optInt("revision") + 1);
        String code = room.getString("code");
        File file = new File(data, code + ".json");
        File tmp = new File(data, code + ".tmp");
        byte[] bytes = room.toString(2).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
        if (!tmp.renameTo(file)) {
            // Some filesystems do not overwrite with renameTo; Android internal storage usually does.
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    File photoDir(String code, String child) throws Exception {
        File folder = new File(new File(data, code), child);
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Impossível criar pasta");
        return folder;
    }

    File boardPhoto(String code) throws Exception { return new File(photoDir(code, ""), "board.jpg"); }

    static String clip(String s, int max) { return s == null ? "" : s.substring(0, Math.min(s.length(), max)); }
}

final class ApiException extends Exception {
    final int status;
    ApiException(int status, String reason) { super(reason); this.status = status; }
}
