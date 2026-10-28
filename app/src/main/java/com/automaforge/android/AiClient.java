package com.automaforge.android;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Optional cloud inference; UI and games remain local. Never forward the API key to browsers. */
final class AiClient {
    static final class RuleFile {
        final byte[] bytes;
        final String mimeType;
        final String fileName;

        RuleFile(byte[] bytes, String mimeType, String fileName) {
            this.bytes = bytes;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }

        static RuleFile image(byte[] bytes) { return new RuleFile(bytes, "image/jpeg", "manual.jpg"); }
    }

    private final Context context;
    AiClient(Context context) { this.context = context.getApplicationContext(); }
    String key() { return new ApiKeyStore(context).read(); }
    boolean enabled() { return !key().isEmpty(); }
    String model() { return context.getSharedPreferences("private_settings", 0).getString("model", "gpt-4.1-mini"); }

    private static byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private JSONObject schema(String name) throws Exception {
        try (InputStream in = context.getAssets().open("schemas/" + name + ".json")) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    JSONObject ask(String instructions, String schema, List<RuleFile> files) throws Exception {
        if (!enabled()) throw new ApiException(409, "Configure a chave OpenAI no aparelho controlador para ativar IA.");
        JSONArray content = new JSONArray();
        content.put(GameStore.obj("type", "input_text", "text", instructions));
        if (files != null) for (RuleFile file : files) {
            String encoded = Base64.encodeToString(file.bytes, Base64.NO_WRAP);
            if ("application/pdf".equals(file.mimeType)) {
                content.put(GameStore.obj("type", "input_file", "filename", file.fileName,
                    "file_data", "data:application/pdf;base64," + encoded));
            } else {
                content.put(GameStore.obj("type", "input_image",
                    "image_url", "data:" + file.mimeType + ";base64," + encoded, "detail", "high"));
            }
        }
        JSONObject schemaFormat = GameStore.obj("type", "json_schema", "name", "automaforge_" + schema,
                "schema", schema(schema), "strict", true);
        JSONObject body = GameStore.obj("model", model(), "store", false,
            "input", new JSONArray().put(GameStore.obj("role", "user", "content", content)),
            "text", GameStore.obj("format", schemaFormat));
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
        try {
            conn.setRequestMethod("POST"); conn.setConnectTimeout(18000); conn.setReadTimeout(100000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key());
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(502, "Serviço de IA retornou HTTP " + status + ". Confira a chave, o modelo e os limites.");
            JSONObject response = new JSONObject(new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8));
            JSONArray output = response.optJSONArray("output");
            if (output != null) for (int i=0; i<output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONArray blocks = item.optJSONArray("content");
                if (blocks == null) continue;
                for (int j=0; j<blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block != null && "output_text".equals(block.optString("type"))) {
                        try {
                            JSONObject value=new JSONObject(block.optString("text"));
                            RuleJsonValidator.INSTANCE.validate(value,schema(schema));return value;
                        } catch(Exception e) { throw new ApiException(502,"Resposta da IA fora do esquema esperado; análise não concluída."); }
                    }
                }
            }
            throw new ApiException(502, "IA não retornou JSON interpretável.");
        } finally { conn.disconnect(); }
    }

    JSONObject rules(String name, List<RuleFile> files) throws Exception {
        return ask(AiPrompts.rules(name), "rules", files);
    }

    JSONObject board(JSONObject room, byte[] photo) throws Exception {
        String state = GameStore.obj("game_state", GameEngine.state(room), "board_version", room.optInt("board_version"),
            "photo_at", room.optString("photo_at"), "photo_state_version", room.optInt("photo_state_version",-1)).toString();
        return ask(AiPrompts.board(room.optString("rules_text"), state), "board",
            java.util.Collections.singletonList(RuleFile.image(photo)));
    }

    JSONObject privateCard(JSONObject room, byte[] photo) throws Exception {
        return ask(AiPrompts.privateCard(room.optString("rules_text")), "card",
            java.util.Collections.singletonList(RuleFile.image(photo)));
    }

    JSONObject propose(JSONObject room, JSONArray privateCards, JSONArray errors) throws Exception {
        JSONObject context = GameStore.obj(
            "game_name", room.optString("game_name"),
            "rules_text", room.optString("rules_text"),
            "setup", room.optString("setup"),
            "turn_flow", room.optString("turn_flow"),
            "actions", room.optJSONArray("actions"),
            "uncertainties", room.optJSONArray("uncertainties"),
            "turn", room.optInt("turn"),
            "phase", room.optString("phase"),
            "variables", room.optJSONObject("variables"),
            "board_notes", room.optString("board_notes"),
            "recent_history", tail(room.optJSONArray("history"), 8));
        context.put("game_state",GameEngine.state(room));
        context.put("rules_revision",room.optInt("rules_revision"));
        context.put("observation",room.optJSONObject("board_observation"));
        context.put("repair_errors",errors);
        context.put("private_cards_ONLY_FOR_AI", privateCards);
        if(context.toString().length()>180000)throw new ApiException(422,"Contexto grande demais; reduza a etapa sem descartar dados necessários.");
        JSONObject answer=ask(AiPrompts.proposal(context.toString()), "proposal", null);
        JSONArray entries=answer.optJSONArray("parameters");JSONObject params=new JSONObject();
        if(entries!=null)for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);String name=entry.getString("name");
            if(params.has(name))throw new ApiException(422,"IA repetiu um parâmetro.");params.put(name,entry.get("value"));
        }
        answer.put("parameters",params);return answer;
    }

    private static JSONArray tail(JSONArray all, int count) {
        JSONArray out = new JSONArray();
        if (all == null) return out;
        for (int i=Math.max(0, all.length()-count); i<all.length(); i++) out.put(all.opt(i));
        return out;
    }
}
