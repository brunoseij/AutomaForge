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
    private final Context context;
    AiClient(Context context) { this.context = context.getApplicationContext(); }
    String key() { return context.getSharedPreferences("private_settings", 0).getString("api_key", "").trim(); }
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

    JSONObject ask(String instructions, String schema, List<byte[]> photos) throws Exception {
        if (!enabled()) throw new ApiException(409, "Configure a chave OpenAI no aparelho controlador para ativar IA.");
        JSONArray content = new JSONArray();
        content.put(GameStore.obj("type", "input_text", "text", instructions));
        if (photos != null) for (byte[] photo : photos) {
            String encoded = Base64.encodeToString(photo, Base64.NO_WRAP);
            content.put(GameStore.obj("type", "input_image", "image_url", "data:image/jpeg;base64," + encoded, "detail", "high"));
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
                        return new JSONObject(block.optString("text"));
                    }
                }
            }
            throw new ApiException(502, "IA não retornou JSON interpretável.");
        } finally { conn.disconnect(); }
    }

    JSONObject rules(String name, List<byte[]> pages) throws Exception {
        return ask("Leia SOMENTE estas páginas de manual de jogo de tabuleiro. Nome informado: " + name +
            ". Responda em português. Extraia regras aplicáveis a um adversário artificial, configuração e fluxo de turnos; " +
            "ações baseadas em regras, com condições. Não invente conteúdo ausente. Se o manual não tiver modo solo, " +
            "as ações são hipóteses de emulação de jogador. Informe incertezas. Leia as páginas na ordem enviada.", "rules", pages);
    }

    JSONObject board(JSONObject room, byte[] photo) throws Exception {
        return ask("Analise o tabuleiro com cautela. Descreva somente o visível e possíveis mudanças, sem assumir elementos ocultos. " +
            "Não modifique o estado da partida. Responda em português. Regras: " +
            GameStore.clip(room.optString("rules_text"), 16000) + " Estado: " +
            GameStore.obj("turn",room.optInt("turn"), "phase",room.optString("phase"),
                "variables",room.optJSONObject("variables")).toString(), "board", java.util.Collections.singletonList(photo));
    }

    JSONObject privateCard(JSONObject room, byte[] photo) throws Exception {
        return ask("Leia esta carta privada da automa. Responda em português e descreva somente o conteúdo realmente legível. " +
            "Não invente texto. A leitura é exclusiva da automa, não deve ser mostrada ao jogador. Regras: " +
            GameStore.clip(room.optString("rules_text"), 10000), "card", java.util.Collections.singletonList(photo));
    }

    JSONObject propose(JSONObject room, JSONArray privateCards) throws Exception {
        JSONObject context = new JSONObject(room.toString());
        context.remove("rule_pages"); context.remove("rules_draft");
        context.remove("history");
        context.put("recent_history", tail(room.optJSONArray("history"), 8));
        context.put("private_cards_ONLY_FOR_AI", privateCards);
        return ask("Você é o adversário artificial do jogo. Escolha exatamente uma ação pelo NOME da lista de ações permitidas, " +
            "sem inventar movimentos ou condições. Considere o contexto e as cartas privadas apenas para decidir, " +
            "NUNCA revele seu conteúdo em spoken, reason ou player_steps. Fale naturalmente em português na primeira pessoa. " +
            "Estado incerto significa confiança baixa; se for incerto, não proponha mudanças de estado. " +
            "Jogadas sempre passam por aprovação humana. Contexto: " + GameStore.clip(context.toString(), 30000),
            "proposal", null);
    }

    private static JSONArray tail(JSONArray all, int count) {
        JSONArray out = new JSONArray();
        if (all == null) return out;
        for (int i=Math.max(0, all.length()-count); i<all.length(); i++) out.put(all.opt(i));
        return out;
    }
}
