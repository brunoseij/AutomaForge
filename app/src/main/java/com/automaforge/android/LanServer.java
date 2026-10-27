package com.automaforge.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Self-contained LAN HTTP + WebSocket server. Uses ONLY Android/Java built-in APIs. */
final class LanServer {
    static final int PORT = 8765;
    private static final int MAX_BODY = 30 * 1024 * 1024;
    private final Context context;
    private final GameStore store;
    private final AiClient ai;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, java.util.Set<WsClient>> sockets = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket listener;

    LanServer(Context ctx) { context = ctx.getApplicationContext(); store = new GameStore(context); ai = new AiClient(context); }
    boolean isRunning() { return running; }

    synchronized void start() throws IOException {
        if (running) return;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new java.net.InetSocketAddress("0.0.0.0", PORT));
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket client = listener.accept();
                    client.setSoTimeout(600000);
                    pool.execute(() -> serve(client));
                } catch (IOException e) { if (running) android.util.Log.e("AutomaForge", "accept", e); }
            }
        });
    }
    synchronized void stop() {
        running = false;
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        for (java.util.Set<WsClient> set : sockets.values()) for (WsClient socket : set) socket.close();
        sockets.clear();
        // On next app launch, sessions reload from internal files; private cards are intentionally ephemeral.
    }

    private static class Request {
        String method, path;
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        String header(String key) { return headers.getOrDefault(key.toLowerCase(Locale.ROOT), ""); }
        JSONObject json() throws Exception {
            if (body.length > 1024*1024) throw new ApiException(413, "JSON grande demais.");
            try { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new ApiException(400, "JSON inválido."); }
        }
    }
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (line.size() > 16384) throw new ApiException(431, "Cabeçalho muito longo.");
            if (c != '\r') line.write(c);
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("ISO-8859-1");
    }
    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length]; int pos = 0;
        while (pos < length) { int got = input.read(bytes, pos, length-pos); if (got < 0) throw new ApiException(400, "Upload incompleto."); pos += got; }
        return bytes;
    }
    private static Request parse(InputStream in) throws Exception {
        String line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) throw new ApiException(400, "Requisição inválida.");
        Request req = new Request(); req.method = parts[0].toUpperCase(Locale.ROOT);
        req.path = parts[1].split("\\?",2)[0];
        if (!req.path.startsWith("/")) throw new ApiException(400, "Rota inválida.");
        for (int i=0; i<60; i++) {
            line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) req.headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT), line.substring(colon+1).trim());
        }
        if (req.header("transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked")) throw new ApiException(411, "Envie Content-Length.");
        String len = req.header("content-length");
        int size;
        try { size = len.isEmpty() ? 0 : Integer.parseInt(len); }
        catch (NumberFormatException e) { throw new ApiException(400, "Content-Length inválido."); }
        if (size < 0 || size > MAX_BODY) throw new ApiException(413, "Upload excedeu 30 MB.");
        if (size > 0) req.body = readExactly(in, size);
        return req;
    }

    private static final class Response {
        int status; byte[] body; String contentType;
        Response(int status, byte[] body, String type) { this.status = status; this.body=body; this.contentType=type; }
    }
    private static final class UploadFile {
        final byte[] bytes;
        final String mimeType;
        UploadFile(byte[] bytes, String mimeType) { this.bytes=bytes; this.mimeType=mimeType; }
    }
    private static Response json(int status, Object value) {
        return new Response(status, String.valueOf(value).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }
    private static Response success(Object value) { return json(200, value); }
    private static Response image(byte[] data) { return new Response(200, data, "image/jpeg"); }
    private static String reason(int status) {
        switch(status) { case 200:return "OK";case 101:return "Switching Protocols";case 400:return "Bad Request";
            case 403:return "Forbidden";case 404:return "Not Found";case 409:return "Conflict";
            case 411:return "Length Required";case 413:return "Payload Too Large";case 422:return "Unprocessable Entity";
            case 431:return "Request Header Fields Too Large";case 502:return "Bad Gateway";default:return "Internal Server Error"; }
    }
    private static void reply(OutputStream out, Response response) throws Exception {
        String headers = "HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n"+
            "Content-Type: " + response.contentType + "\r\nContent-Length: " + response.body.length +
            "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+
            "Content-Security-Policy: default-src 'self' data: blob: 'unsafe-inline'; img-src 'self' data: blob:; " +
            "connect-src 'self' ws: wss:;\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(response.body); out.flush();
    }
    private void serve(Socket socket) {
        try (Socket client = socket) {
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            try {
                Request request = parse(input);
                if (request == null) return;
                if (request.path.startsWith("/ws/") && "websocket".equalsIgnoreCase(request.header("upgrade"))) {
                    websocket(request, input, out, client); return;
                }
                reply(out, dispatch(request, client));
            } catch (ApiException ex) { reply(out, json(ex.status, GameStore.obj("detail", ex.getMessage()))); }
            catch (Exception ex) {
                android.util.Log.e("AutomaForge", "Request failed", ex);
                reply(out, json(500, GameStore.obj("detail", "Erro interno. Consulte os registros do Android.")));
            }
        } catch (Exception ignored) {}
    }

    private Response staticFile(String path) throws Exception {
        String file = path.equals("/") ? "index.html" : path.substring("/static/".length());
        if (!file.matches("(index\\.html|app\\.js|style\\.css)")) throw new ApiException(404,"Arquivo não encontrado.");
        String type = file.endsWith(".html") ? "text/html; charset=utf-8" : file.endsWith(".js") ? "application/javascript; charset=utf-8" : "text/css; charset=utf-8";
        try (InputStream in = context.getAssets().open("web/" + file)) {
            return new Response(200, streamBytes(in), type);
        }
    }
    private static byte[] streamBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n=in.read(buffer)) >= 0) out.write(buffer,0,n);
        return out.toByteArray();
    }

    private Response dispatch(Request req, Socket client) throws Exception {
        String path = req.path; String method=req.method;
        if (method.equals("GET") && (path.equals("/") || path.startsWith("/static/"))) return staticFile(path);
        if (path.equals("/api/health") && method.equals("GET")) return success(GameStore.obj("status","ok", "ai_enabled",ai.enabled(), "model",ai.enabled()?ai.model():JSONObject.NULL, "https",false, "android_host",true));
        if (path.equals("/api/rooms") && method.equals("POST")) {
            synchronized(store) { return success(store.create(req.json().optString("game_name", "Jogo sem nome"))); }
        }
        if (!path.startsWith("/api/rooms/")) throw new ApiException(404, "Rota não encontrada.");
        String[] p = path.substring("/api/rooms/".length()).split("/");
        if (p.length < 1) throw new ApiException(404,"Sala não encontrada.");
        String code = p[0].toUpperCase(Locale.ROOT);
        // Reject path traversal with the same validation used by GameStore.
        synchronized(store) { store.get(code); }
        String rest = path.substring("/api/rooms/".length()+p[0].length());
        if (method.equals("GET") && rest.isEmpty()) { synchronized(store) { return success(store.publicRoom(store.get(code))); } }
        if (method.equals("PUT") && rest.equals("/rules")) {
            JSONObject body=req.json();
            synchronized(store) {
                JSONObject r=store.get(code);
                r.put("game_name",GameStore.clip(body.optString("game_name","Jogo sem nome"),100));
                r.put("rules_text",GameStore.clip(body.optString("rules_text"),60000));
                r.put("setup",GameStore.clip(body.optString("setup"),12000));
                r.put("turn_flow",GameStore.clip(body.optString("turn_flow"),12000));
                r.put("actions",normalizeActions(body.optJSONArray("actions")));
                r.put("uncertainties",body.optJSONArray("uncertainties")!=null?body.optJSONArray("uncertainties"):new JSONArray());
                r.put("rules_draft",JSONObject.NULL);r.put("proposal",JSONObject.NULL);
                store.save(r,true);broadcast(code);return success(store.publicRoom(r));
            }
        }
        if (method.equals("POST") && rest.equals("/rules/pages")) {
            List<UploadFile> files=files(req);
            synchronized(store) {
                JSONObject r=store.get(code);JSONArray names=r.getJSONArray("rule_pages");
                if (files.isEmpty() || files.size()>12 || files.size()+names.length()>30) throw new ApiException(400,"Limite de 12 arquivos por envio e 30 por sala.");
                File folder=store.photoDir(code,"rules");
                for (UploadFile content: files) {
                    boolean pdf="application/pdf".equals(content.mimeType);
                    if (pdf && !isPdf(content.bytes)) throw new ApiException(400,"Arquivo PDF inválido.");
                    String name=UUID.randomUUID().toString()+(pdf?".pdf":".jpg");
                    byte[] stored=pdf?content.bytes:jpeg(content.bytes,1600);
                    Files.write(new File(folder,name).toPath(), stored);
                    names.put(name);
                }
                r.put("rules_draft",JSONObject.NULL);store.save(r,true);broadcast(code);
                return success(GameStore.obj("pages_count",names.length()));
            }
        }
        if (method.equals("POST") && rest.equals("/rules/interpret")) {
            JSONObject snapshot;List<AiClient.RuleFile> files=new ArrayList<>();int revision;
            synchronized(store) {
                JSONObject r=store.get(code);revision=r.optInt("revision");snapshot=new JSONObject(r.toString());
                JSONArray names=r.getJSONArray("rule_pages");
                if (names.length()==0)throw new ApiException(409,"Envie o PDF ou fotografias do manual.");
                for (int i=0; i<names.length(); i++) {
                    String name=names.getString(i);
                    byte[] content=Files.readAllBytes(new File(store.photoDir(code,"rules"),name).toPath());
                    files.add(name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                        ? new AiClient.RuleFile(content,"application/pdf","manual.pdf")
                        : AiClient.RuleFile.image(content));
                }
            }
            if (!ai.enabled()) throw new ApiException(409,"Configure a chave de IA no controlador ou preencha as regras manualmente.");
            JSONObject draft = new JSONObject(); JSONArray actions = new JSONArray(); JSONArray uncertainties=new JSONArray();
            StringBuilder rules=new StringBuilder(), setup=new StringBuilder(),turns=new StringBuilder();
            for (int start=0; start<files.size(); start+=12) {
                int end=Math.min(start+12,files.size());
                JSONObject part=ai.rules(snapshot.optString("game_name"),files.subList(start,end));
                String label="\n[Arquivos "+(start+1)+"–"+end+"]\n";
                rules.append(label).append(part.optString("rules_text"));setup.append(label).append(part.optString("setup"));
                turns.append(label).append(part.optString("turn_flow"));
                JSONArray a=part.optJSONArray("actions");if(a!=null)for(int i=0;i<a.length();i++)actions.put(a.get(i));
                JSONArray u=part.optJSONArray("uncertainties");if(u!=null)for(int i=0;i<u.length();i++)uncertainties.put(u.get(i));
            }
            if (files.size()>12) uncertainties.put("Interpretação em lotes; revise condições entre páginas de lotes diferentes.");
            draft.put("game_name",snapshot.optString("game_name"));draft.put("rules_text",rules.toString());
            draft.put("setup",setup.toString());draft.put("turn_flow",turns.toString());
            draft.put("actions",normalizeActions(actions));draft.put("uncertainties",uncertainties);
            synchronized(store) {
                JSONObject r=store.get(code);
                if(r.optInt("revision")!=revision)throw new ApiException(409,"As regras mudaram durante a análise. Tente novamente.");
                r.put("rules_draft",draft);store.save(r,true);broadcast(code);
                return success(draft);
            }
        }
        if (method.equals("PUT") && rest.equals("/state")) {
            JSONObject body=req.json();int turn=body.optInt("turn",0);
            if(turn<1 || turn>1000000)throw new ApiException(400,"Turno deve ser positivo.");
            JSONObject vars=body.optJSONObject("variables");if(vars==null)vars=new JSONObject();
            if(vars.length()>100)throw new ApiException(400,"Limite de 100 variáveis.");
            java.util.Iterator<String> it=vars.keys();while(it.hasNext()) {
                String key=it.next();if(key.length()>100 || vars.optString(key).length()>1000)throw new ApiException(400,"Variável grande demais.");
            }
            synchronized(store) {
                JSONObject r=store.get(code);r.put("turn",turn);r.put("phase",GameStore.clip(body.optString("phase"),140));
                r.put("board_notes",GameStore.clip(body.optString("board_notes"),12000));r.put("variables",vars);
                store.save(r,true);broadcast(code);return success(store.publicRoom(r));
            }
        }
        if (method.equals("POST") && rest.equals("/board/photo")) {
            byte[] content=files(req).get(0).bytes;byte[] compressed=jpeg(content,1280);
            synchronized(store) {
                JSONObject r=store.get(code);Files.write(store.boardPhoto(code).toPath(),compressed);
                int version=r.optInt("board_version")+1;r.put("board_version",version);
                store.save(r,false);broadcastEvent(code,GameStore.obj("type","board","board_version",version));
                return success(GameStore.obj("board_version",version));
            }
        }
        if (method.equals("GET") && rest.equals("/board/photo")) {
            synchronized(store) { File file=store.boardPhoto(code);if(!file.exists())throw new ApiException(404,"Foto indisponível.");return image(Files.readAllBytes(file.toPath())); }
        }
        if (method.equals("POST") && rest.equals("/board/analyze")) {
            JSONObject snapshot;byte[] photo;int revision;
            synchronized(store) {
                JSONObject r=store.get(code);revision=r.optInt("revision");snapshot=new JSONObject(r.toString());
                File file=store.boardPhoto(code);if(!file.exists())throw new ApiException(409,"Envie foto do tabuleiro.");
                photo=Files.readAllBytes(file.toPath());
            }
            JSONObject observation=ai.board(snapshot,photo);
            synchronized(store) {
                JSONObject r=store.get(code);if(r.optInt("revision")!=revision)throw new ApiException(409,"Estado mudou durante análise.");
                r.put("board_observation",observation);store.save(r,true);broadcast(code);return success(observation);
            }
        }
        if (method.equals("POST") && rest.equals("/cards/private")) {
            byte[] photo=jpeg(files(req).get(0).bytes,1600);JSONObject snapshot;
            synchronized(store) {
                if(store.privateCards(code).length()>=40)throw new ApiException(409,"Limite de 40 cartas privadas.");
                snapshot=new JSONObject(store.get(code).toString());
            }
            JSONObject reading=ai.privateCard(snapshot,photo);
            synchronized(store) {
                JSONArray privateCards=store.privateCards(code);if(privateCards.length()>=40)throw new ApiException(409,"Limite de cartas atingido.");
                privateCards.put(reading);
                // No image, card text or any sensitive interpretation written to disk or public JSON.
                broadcast(code);
                return success(GameStore.obj("stored",true,"private_card_count",privateCards.length(),
                    "has_uncertainties",reading.optJSONArray("uncertainties")!=null&&reading.optJSONArray("uncertainties").length()>0));
            }
        }
        if (method.equals("DELETE") && rest.equals("/cards/private")) {
            synchronized(store) {store.clearPrivate(code);broadcast(code);return success(GameStore.obj("private_card_count",0));}
        }
        if (method.equals("POST") && rest.equals("/automa/manual")) {
            JSONObject body=req.json();
            synchronized(store) {
                JSONObject r=store.get(code);
                if(!r.isNull("proposal"))throw new ApiException(409,"Resolva a jogada pendente.");
                JSONObject proposal=validateProposal(body,r);
                r.put("proposal",proposal);store.save(r,true);broadcast(code);return success(proposal);
            }
        }
        if (method.equals("POST") && rest.equals("/automa/propose")) {
            JSONObject snapshot;JSONArray cards;int revision;
            synchronized(store) {
                JSONObject r=store.get(code);revision=r.optInt("revision");
                if(!r.isNull("proposal"))throw new ApiException(409,"Resolva a jogada pendente.");
                if(r.getJSONArray("actions").length()==0)throw new ApiException(409,"Cadastre pelo menos uma ação.");
                snapshot=new JSONObject(r.toString());cards=new JSONArray(store.privateCards(code).toString());
            }
            JSONObject proposalData;
            if (!ai.enabled()) throw new ApiException(409,"Configure a IA no celular controlador ou use Escolher manualmente.");
            proposalData=ai.propose(snapshot,cards);
            if(cards.length()>0) {
                // Never publish any generated prose derived from private cards: only the chosen public action name.
                String name=proposalData.optString("action");JSONArray actions=snapshot.getJSONArray("actions");
                for(int i=0;i<actions.length();i++){
                    JSONObject action=actions.getJSONObject(i);
                    if(name.equals(action.optString("name"))) {
                        proposalData.put("reason","Ação escolhida considerando o estado da partida e informações privadas não reveladas.");
                        proposalData.put("spoken","Minha jogada é: "+name+".");
                        JSONArray steps=new JSONArray();if(!action.optString("description").isEmpty())steps.put(action.optString("description"));
                        proposalData.put("player_steps",steps);proposalData.put("state_changes",new JSONArray());
                        proposalData.put("uncertainties",new JSONArray().put("Verifique se a ação é legal no estado atual."));
                        break;
                    }
                }
            }
            synchronized(store) {
                JSONObject r=store.get(code);
                if (r.optInt("revision")!=revision || !r.isNull("proposal")) throw new ApiException(409,"O estado mudou durante a sugestão.");
                JSONObject proposal=validateProposal(proposalData,r);
                r.put("proposal",proposal);store.save(r,true);broadcast(code);return success(proposal);
            }
        }
        if (method.equals("POST") && rest.equals("/automa/reject")) {
            synchronized(store) {
                JSONObject r=store.get(code);r.put("proposal",JSONObject.NULL);store.save(r,true);broadcast(code);
                return success(store.publicRoom(r));
            }
        }
        if (method.equals("POST") && rest.equals("/automa/confirm")) {
            synchronized(store) {
                JSONObject r=store.get(code);JSONObject proposal=r.optJSONObject("proposal");
                if(proposal==null)throw new ApiException(409,"Nenhuma jogada pendente.");
                JSONArray changes=proposal.optJSONArray("state_changes");JSONObject vars=r.getJSONObject("variables");
                if(changes!=null) for(int i=0;i<changes.length();i++) {
                    JSONObject entry=changes.optJSONObject(i);if(entry!=null && !entry.optString("key").isEmpty())
                        vars.put(entry.getString("key"),entry.optString("value"));
                }
                JSONArray history=r.getJSONArray("history");
                history.put(GameStore.obj("turn",r.optInt("turn"),"action",proposal.optString("action"),
                    "reason",proposal.optString("reason"),"spoken",proposal.optString("spoken"),
                    "player_steps",proposal.optJSONArray("player_steps"),"at",GameStore.now()));
                if(history.length()>100){JSONArray trimmed=new JSONArray();for(int i=history.length()-100;i<history.length();i++) trimmed.put(history.get(i));r.put("history",trimmed);}
                r.put("last_announcement",GameStore.obj("id",proposal.optString("id"),"text",proposal.optString("spoken")));
                r.put("turn",r.optInt("turn")+1);r.put("proposal",JSONObject.NULL);
                store.save(r,true);broadcast(code);return success(store.publicRoom(r));
            }
        }
        throw new ApiException(404,"Rota não encontrada.");
    }

    private static JSONArray normalizeActions(JSONArray source) {
        JSONArray out=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        if(source==null)return out;
        for(int i=0;i<Math.min(source.length(),100);i++) {
            JSONObject action=source.optJSONObject(i);if(action==null)continue;
            String name=GameStore.clip(action.optString("name").trim(),160);
            if(name.isEmpty()||!seen.add(name.toLowerCase(Locale.ROOT)))continue;
            out.put(GameStore.obj("name",name,"description",GameStore.clip(action.optString("description"),1500),
                "precondition",GameStore.clip(action.optString("precondition"),1500)));
        }
        return out;
    }
    private static JSONObject validateProposal(JSONObject proposal,JSONObject room) throws Exception {
        String name=proposal.optString("action");boolean legal=false;
        JSONArray actions=room.getJSONArray("actions");for(int i=0;i<actions.length();i++) {
            if(name.equals(actions.getJSONObject(i).optString("name"))) {legal=true;break;}
        }
        if(!legal)throw new ApiException(422,"Ação não cadastrada na lista de ações permitidas.");
        JSONArray steps=boundedArray(proposal.optJSONArray("player_steps"),30,500);
        JSONArray uncertainties=boundedArray(proposal.optJSONArray("uncertainties"),30,500);
        JSONArray changes=new JSONArray();JSONArray source=proposal.optJSONArray("state_changes");
        if(source!=null)for(int i=0;i<Math.min(source.length(),30);i++) {
            JSONObject entry=source.optJSONObject(i);if(entry!=null) changes.put(GameStore.obj("key",GameStore.clip(entry.optString("key"),100),
                "value",GameStore.clip(entry.optString("value"),1000)));
        }
        String confidence=proposal.optString("confidence","baixa");
        if(!Arrays.asList("baixa","media","alta").contains(confidence))confidence="baixa";
        return GameStore.obj("id",UUID.randomUUID().toString(),"action",name,
            "reason",GameStore.clip(proposal.optString("reason"),4000),"spoken",GameStore.clip(proposal.optString("spoken"),2500),
            "player_steps",steps,"state_changes",changes,"uncertainties",uncertainties,"confidence",confidence);
    }
    private static JSONArray boundedArray(JSONArray input,int limit,int chars) {
        JSONArray result=new JSONArray();if(input==null)return result;
        for(int i=0;i<Math.min(limit,input.length());i++)result.put(GameStore.clip(input.optString(i),chars));
        return result;
    }

    private static int locate(byte[] haystack,byte[] needle,int from) {
        outer:for(int i=from;i<=haystack.length-needle.length;i++) {
            for(int j=0;j<needle.length;j++)if(haystack[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }
    private static List<UploadFile> files(Request req) throws Exception {
        String type=req.header("content-type");
        if(type.regionMatches(true,0,"image/",0,6)||type.regionMatches(true,0,"application/pdf",0,15)) {
            if(req.body.length==0)throw new ApiException(400,"Arquivo vazio.");
            if(req.body.length>10*1024*1024)throw new ApiException(413,"Arquivo maior que 10 MB.");
            String mime=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if (!"application/pdf".equals(mime) && !mime.startsWith("image/"))
                throw new ApiException(400,"Envie uma imagem ou um arquivo PDF.");
            if ("application/pdf".equals(mime) && !isPdf(req.body)) throw new ApiException(400,"Arquivo PDF inválido.");
            return Collections.singletonList(new UploadFile(req.body,mime));
        }
        if(!type.regionMatches(true,0,"multipart/form-data",0,19))throw new ApiException(400,"Envie uma imagem, um PDF ou um formulário multipart.");
        String boundary=null;for(String option:type.split(";")) {
            String s=option.trim();if(s.startsWith("boundary=")) boundary=s.substring(9).replace("\"", "");
        }
        if(boundary==null || boundary.isEmpty() || boundary.length()>150)throw new ApiException(400,"Limite multipart inválido.");
        byte[] prefix=("--"+boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] next=("\r\n--"+boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separator="\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        List<UploadFile> result=new ArrayList<>();int pos=locate(req.body,prefix,0);
        while(pos>=0) {
            int head=pos+prefix.length;
            if(head+1<req.body.length && req.body[head]=='-'&&req.body[head+1]=='-')break;
            int endHeaders=locate(req.body,separator,head);
            if(endHeaders<0)throw new ApiException(400,"Upload multipart inválido.");
            String headers=new String(req.body,head,endHeaders-head,StandardCharsets.ISO_8859_1);
            int dataStart=endHeaders+separator.length;
            int dataEnd=locate(req.body,next,dataStart);
            if(dataEnd<0)throw new ApiException(400,"Upload multipart incompleto.");
            if(headers.toLowerCase(Locale.ROOT).contains("filename=")) {
                if(dataEnd-dataStart>10*1024*1024)throw new ApiException(413,"Arquivo maior que 10 MB.");
                byte[] content=Arrays.copyOfRange(req.body,dataStart,dataEnd);
                String partType=partHeader(headers,"content-type").toLowerCase(Locale.ROOT);
                String disposition=partHeader(headers,"content-disposition");
                String fileName=partFileName(disposition).toLowerCase(Locale.ROOT);
                String mime=partType.split(";",2)[0].trim();
                if (mime.isEmpty() || "application/octet-stream".equals(mime)) mime=mimeFromName(fileName);
                if (!isSupportedUploadType(mime)) throw new ApiException(400,"Envie uma imagem JPEG, PNG ou WebP, ou um arquivo PDF.");
                if ("application/pdf".equals(mime) && !isPdf(content)) throw new ApiException(400,"Arquivo PDF inválido.");
                result.add(new UploadFile(content,mime));
            }
            pos=dataEnd+2;
        }
        if(result.isEmpty())throw new ApiException(400,"Selecione ao menos um arquivo.");
        return result;
    }
    private static String partHeader(String headers,String name) {
        for(String line:headers.split("\r\n")) {
            int colon=line.indexOf(':');
            if(colon>0&&line.substring(0,colon).trim().equalsIgnoreCase(name))return line.substring(colon+1).trim();
        }
        return "";
    }
    private static String partFileName(String disposition) {
        for(String parameter:disposition.split(";")) {
            String value=parameter.trim();
            if(value.regionMatches(true,0,"filename=",0,9)) {
                String name=value.substring(9).trim();
                if(name.length()>=2&&name.startsWith("\"")&&name.endsWith("\""))name=name.substring(1,name.length()-1);
                return name;
            }
        }
        return "";
    }
    private static String mimeFromName(String name) {
        if(name.endsWith(".pdf"))return "application/pdf";
        if(name.endsWith(".png"))return "image/png";
        if(name.endsWith(".webp"))return "image/webp";
        if(name.endsWith(".jpg")||name.endsWith(".jpeg"))return "image/jpeg";
        return "";
    }
    private static boolean isSupportedUploadType(String mime) {
        return "application/pdf".equals(mime)||"image/jpeg".equals(mime)||"image/png".equals(mime)||"image/webp".equals(mime);
    }
    private static boolean isPdf(byte[] bytes) {
        return bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-';
    }
    private static byte[] jpeg(byte[] bytes,int maxSide) throws Exception {
        if(bytes.length>10*1024*1024)throw new ApiException(413,"Foto maior que 10 MB.");
        BitmapFactory.Options first=new BitmapFactory.Options();first.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,first);
        if(first.outWidth<1||first.outHeight<1||first.outWidth>16000||first.outHeight>16000)
            throw new ApiException(400,"Imagem inválida ou grande demais.");
        BitmapFactory.Options opts=new BitmapFactory.Options();
        int max=Math.max(first.outWidth,first.outHeight);opts.inSampleSize=1;
        while(max / opts.inSampleSize > maxSide*2)opts.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
        if(bitmap==null)throw new ApiException(400,"Não foi possível abrir esta imagem.");
        Bitmap resized=bitmap;
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        if(Math.max(width,height)>maxSide) {
            float scale=(float)maxSide/Math.max(width,height);
            resized=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),true);
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        boolean ok=resized.compress(Bitmap.CompressFormat.JPEG,85,out);
        if(resized!=bitmap)resized.recycle();bitmap.recycle();
        if(!ok)throw new ApiException(400,"Não foi possível converter imagem.");
        return out.toByteArray();
    }

    private final class WsClient {
        final OutputStream out;final Socket connection;final String code;
        WsClient(OutputStream out,Socket connection,String code){this.out=out;this.connection=connection;this.code=code;}
        synchronized void send(JSONObject obj) throws IOException {
            byte[] body=obj.toString().getBytes(StandardCharsets.UTF_8);
            out.write(0x81);
            if(body.length<126)out.write(body.length);
            else if(body.length<=65535){out.write(126);out.write(body.length>>8);out.write(body.length);}
            else {out.write(127);for(int i=7;i>=0;i--)out.write((int)(((long)body.length >>> (8*i)) & 255));}
            out.write(body);out.flush();
        }
        void close(){try{connection.close();}catch(Exception ignored){}}
    }
    private void websocket(Request req,InputStream in,OutputStream out,Socket socket) throws Exception {
        String code=req.path.substring(4).toUpperCase(Locale.ROOT);
        JSONObject room;
        synchronized(store){room=store.publicRoom(store.get(code));}
        String key=req.header("sec-websocket-key");
        if(key.isEmpty())throw new ApiException(400,"Chave WebSocket ausente.");
        byte[] sha1=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
        String accept=android.util.Base64.encodeToString(sha1,android.util.Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
            "Sec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
        WsClient client=new WsClient(out,socket,code);
        sockets.computeIfAbsent(code,k->ConcurrentHashMap.newKeySet()).add(client);
        try {
            client.send(GameStore.obj("type","state","room",room));
            // Drain client frames to detect closure. Browser need not transmit messages.
            while(running&&!socket.isClosed()) {
                int opcode=in.read();if(opcode<0||(opcode&15)==8)break;
                int b=in.read();if(b<0)break;
                long n=b&127;if(n==126){n=(in.read()<<8)|in.read();}
                else if(n==127){n=0;for(int i=0;i<8;i++)n=(n<<8)|(in.read()&255);}
                if(n>65536)break;
                if((b&128)!=0)readExactly(in,4);
                readExactly(in,(int)n);
            }
        } finally {sockets.getOrDefault(code,Collections.emptySet()).remove(client);}
    }
    private void broadcast(String code) throws Exception {
        broadcastEvent(code,GameStore.obj("type","state","room",store.publicRoom(store.get(code))));
    }
    private void broadcastEvent(String code,JSONObject event) {
        for(WsClient ws:sockets.getOrDefault(code,Collections.emptySet())) {
            try{ws.send(event);}catch(Exception e){sockets.getOrDefault(code,Collections.emptySet()).remove(ws);ws.close();}
        }
    }
}
