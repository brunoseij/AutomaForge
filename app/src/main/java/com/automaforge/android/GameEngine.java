package com.automaforge.android;

import org.json.*;
import java.util.*;

/** Public, game-independent state and sequential simulator. Never interprets prose as a rule. */
final class GameEngine {
    private GameEngine() {}
    static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    static JSONObject state(JSONObject room) throws Exception {
        JSONObject s=room.optJSONObject("game_state");
        if(s==null) {
            s=GameStore.obj("id",UUID.randomUUID().toString(),"version",0,"active_player","", "phase",room.optString("phase"),
                "turn",room.optInt("turn",1),"substep","","metadata_status","unknown", "entities",new JSONArray(),
                "unknowns",new JSONArray().put("Confirme jogador, fase, subetapa, oferta e tabuleiros; notas antigas não são ocupação confirmada."));
            room.put("game_state",s);
            // Legacy proposals have no executable contract and must never be confirmed.
            JSONObject old=room.optJSONObject("proposal");
            if(old!=null&&!old.has("state_id")){room.put("legacy_proposal",old);room.put("proposal",JSONObject.NULL);}
        }
        return s;
    }
    static void changed(JSONObject room) throws Exception {
        JSONObject s=state(room);s.put("version",s.getInt("version")+1);
        room.put("decision",JSONObject.NULL); // Keep a pending proposal visible but stale.
    }
    static JSONObject entity(JSONObject s,String id) {
        JSONArray a=s.optJSONArray("entities");if(a==null)return null;
        for(int i=0;i<a.length();i++){JSONObject e=a.optJSONObject(i);if(e!=null&&id.equals(e.optString("id")))return e;}
        return null;
    }
    static boolean known(JSONObject e) { return e!=null&&Arrays.asList("confirmed","observed").contains(e.optString("status")); }
    static void checkState(JSONObject s) throws Exception {
        JSONArray a=s.getJSONArray("entities");if(a.length()>3000)throw new ApiException(422,"Limite de 3000 entidades públicas.");
        Set<String> ids=new HashSet<>(),anchors=new HashSet<>();
        for(int i=0;i<a.length();i++) {
            JSONObject e=a.getJSONObject(i);String id=e.optString("id");
            if(!id.matches("[A-Za-z0-9_.:-]{1,100}")||!ids.add(id))throw new ApiException(422,"ID inválido ou duplicado: "+id);
            if(!Arrays.asList("confirmed","observed","inferred","unknown").contains(e.optString("status")))throw new ApiException(422,"Situação ausente em "+id);
            if(!"public".equals(e.optString("visibility","public")))throw new ApiException(422,"Estado compartilhado aceita somente informações públicas.");
            if(e.optInt("quantity",0)<0||e.optInt("level",0)<0)throw new ApiException(422,"Quantidade/nível negativo: "+id);
            if("pooled".equals(e.optString("identity"))&&e.optInt("quantity")>0) {
                String anchor=e.optString("kind")+"/"+e.optString("type")+"/"+e.optString("location")+"/"+e.optString("group")+"/"+e.optInt("level");
                if(!anchors.add(anchor))throw new ApiException(422,"Lotes indistinguíveis duplicados no mesmo local/nível: "+id+". Consolide tipo e quantidade.");
            }
            if(e.optString("kind").isEmpty())throw new ApiException(422,"Tipo de entidade ausente: "+id);
        }
        for(int i=0;i<a.length();i++) {
            JSONObject e=a.getJSONObject(i);
            for(String f:Arrays.asList("location","group"))if(!e.optString(f).isEmpty()&&entity(s,e.optString(f))==null)
                throw new ApiException(422,"Referência "+f+" inexistente em "+e.optString("id"));
        }
    }
    /** Full replacement is an explicit human review, guarded by state version. */
    static void correct(JSONObject room,JSONObject input) throws Exception {
        JSONObject current=state(room);
        if(!current.getString("id").equals(input.optString("id"))||current.getInt("version")!=input.optInt("version",-1))
            throw new ApiException(409,"Estado foi atualizado; recarregue antes de corrigir.");
        JSONObject next=copy(input);checkState(next);
        if(next.optInt("turn",0)<1)throw new ApiException(422,"Turno deve ser positivo.");
        JSONArray entities=next.getJSONArray("entities");
        for(int i=0;i<entities.length();i++){
            JSONObject e=entities.getJSONObject(i);e.put("human_locked",true);e.put("source","human");
        }
        next.put("version",current.getInt("version")+1);next.put("metadata_status","confirmed");next.put("metadata_source","human");
        room.put("game_state",next);room.put("phase",next.optString("phase"));room.put("turn",next.optInt("turn",1));
        room.put("decision",JSONObject.NULL);
    }
    /** Observations are candidates, never automatic additions/deletions or replacements of human facts. */
    static JSONObject observation(JSONObject room,JSONObject observation,int photoVersion) throws Exception {
        JSONObject s=state(room);JSONArray conflicts=new JSONArray();JSONArray candidates=observation.optJSONArray("entities");
        if(candidates==null)throw new ApiException(422,"Análise sem entidades estruturadas.");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<candidates.length();i++){
            JSONObject e=candidates.getJSONObject(i);String id=e.optString("id");
            if(id.isEmpty()||!seen.add(id))throw new ApiException(422,"Análise com IDs ausentes ou duplicados.");
            e.put("source","image");e.put("image_version",photoVersion);e.put("human_locked",false);
            if("confirmed".equals(e.optString("status")))e.put("status","observed");
            JSONObject old=entity(s,id);
            if(old!=null) {
                // Identity anchor must remain consistent; uncertain matches go to review.
                if(old.optBoolean("human_locked")||!old.optString("kind").equals(e.optString("kind"))||!old.optString("type").equals(e.optString("type")))
                    conflicts.put("Revisar correspondência/correção humana: "+id);
            }
        }
        observation.put("conflicts",conflicts);observation.put("photo_version",photoVersion);
        observation.put("state_version",s.getInt("version"));observation.put("at",GameStore.now());
        return observation;
    }
    static JSONObject action(JSONObject room,String name) {
        JSONArray actions=room.optJSONArray("actions");if(actions==null)return null;
        for(int i=0;i<actions.length();i++){JSONObject a=actions.optJSONObject(i);if(a!=null&&name.equals(a.optString("name")))return a;}
        return null;
    }
    static boolean contains(JSONArray a,String v) {if(a==null)return false;for(int i=0;i<a.length();i++)if(v.equals(a.optString(i)))return true;return false;}
    static JSONObject report(JSONArray errors,JSONArray missing,JSONObject simulated) {
        JSONArray blockers=new JSONArray();
        for(int i=0;i<missing.length();i++) {
            String m=missing.optString(i);
            if(m.startsWith("Confirme jogador")||m.startsWith("Confirme a entidade")||m.contains("confirme agrupamento")||m.contains("ocupação do destino desconhecida")||m.contains("componente/origem não confirmado"))blockers.put(m);
        }
        return GameStore.obj("status",errors.length()>0?"invalid":missing.length()>0?"unverifiable":"valid",
            "errors",errors,"missing",missing,"blockers",blockers,"simulated",simulated);
    }
    static JSONObject validate(JSONObject room,JSONObject p) throws Exception {
        try {return validateInternal(room,p);}
        catch(JSONException | ClassCastException e) {
            return report(new JSONArray().put("Resposta fora do contrato: campos ou tipos estruturados inválidos."),new JSONArray(),copy(state(room)));
        }
    }
    private static JSONObject validateInternal(JSONObject room,JSONObject p) throws Exception {
        JSONObject s=copy(state(room));JSONArray errors=new JSONArray(),missing=new JSONArray();
        if(!s.getString("id").equals(p.optString("state_id"))||s.getInt("version")!=p.optInt("state_version",-1)
            ||room.optInt("rules_revision")!=p.optInt("rules_revision",-1))errors.put("Proposta baseada em estado/regras antigos.");
        if(!"proposed".equals(p.optString("status")))errors.put("Contrato sem status proposed.");
        for(String field:Arrays.asList("state_id","player","phase","substep","action"))if(!(p.opt(field) instanceof String))errors.put("Campo obrigatório de texto: "+field);
        for(String field:Arrays.asList("state_version","rules_revision"))if(!(p.opt(field) instanceof Integer))errors.put("Campo obrigatório inteiro: "+field);
        JSONObject a=action(room,p.optString("action"));
        if(a==null){errors.put("Ação não cadastrada.");return report(errors,missing,s);}
        if(!a.optBoolean("reviewed")||!a.optBoolean("validation_complete"))missing.put("Cobertura das regras desta ação não foi confirmada por uma pessoa.");
        if(!"confirmed".equals(s.optString("metadata_status"))||s.optString("active_player").isEmpty()||s.optString("phase").isEmpty())missing.put("Confirme jogador ativo, fase e subetapa do estado.");
        if(!s.optString("active_player").equals(p.optString("player")))errors.put("Jogador incorreto.");
        if(!s.optString("phase").equals(p.optString("phase")))errors.put("Fase incorreta.");
        if(!s.optString("substep").equals(p.optString("substep")))errors.put("Subetapa incorreta.");
        if(!a.optString("phase").isEmpty()&&!s.optString("phase").equals(a.optString("phase")))errors.put("Ação indisponível nesta fase.");
        if(!a.optString("player").isEmpty()&&!s.optString("active_player").equals(a.optString("player")))errors.put("Ação indisponível para o jogador.");
        JSONArray required=a.optJSONArray("required_state");
        if(required==null)missing.put("Defina os dados de estado necessários à ação.");
        else for(int i=0;i<required.length();i++) {
            String id=required.getString(i);JSONObject e=entity(s,id);
            if(!known(e))missing.put("Confirme a entidade necessária: "+id);
        }
        JSONObject params=p.optJSONObject("parameters");JSONArray defs=a.optJSONArray("parameters");
        if(params==null){errors.put("Parâmetros ausentes.");params=new JSONObject();}
        if(defs==null)missing.put("Defina os parâmetros da ação.");
        else {
            Set<String> names=new HashSet<>();
            for(int i=0;i<defs.length();i++) {
                JSONObject d=defs.getJSONObject(i);String name=d.optString("name"),type=d.optString("type");names.add(name);
                Object value=params.opt(name);
                if(value==null||value==JSONObject.NULL){if(d.optBoolean("required"))errors.put("Parâmetro obrigatório: "+name);continue;}
                boolean ok="string".equals(type)?value instanceof String:"integer".equals(type)?value instanceof Number&&((Number)value).doubleValue()==((Number)value).intValue():"boolean".equals(type)?value instanceof Boolean:false;
                if(!ok)errors.put("Tipo de parâmetro inválido: "+name);
                JSONArray choices=d.optJSONArray("values");
                if(choices!=null&&choices.length()>0&&!contains(choices,String.valueOf(value)))errors.put("Escolha não permitida: "+name);
            }
            Iterator<String> keys=params.keys();while(keys.hasNext()){String key=keys.next();if(!names.contains(key))errors.put("Parâmetro não definido na ação pública.");}
        }
        JSONArray moves=p.optJSONArray("moves");
        if(moves==null||moves.length()==0){errors.put("Uma intenção genérica não é jogada: informe movimentos parametrizados.");return report(errors,missing,s);}
        if(moves.length()>100){errors.put("Limite de 100 movimentos por etapa.");return report(errors,missing,s);}
        if(moves.length()<a.optInt("min_moves",1)||moves.length()>a.optInt("max_moves",100))errors.put("Quantidade de movimentos fora da definição da ação.");
        JSONArray constraints=a.optJSONArray("constraints");if(constraints==null)missing.put("Restrições não definidas.");
        int total=0;String group=null;
        for(int i=0;i<moves.length();i++) {
            JSONObject m=moves.getJSONObject(i);String op=m.optString("operation");
            String prefix="Movimento "+(i+1)+": ";
            for(String field:Arrays.asList("operation","component","source","destination","choice_parameter"))if(!(m.opt(field) instanceof String))errors.put(prefix+"campo de texto ausente: "+field);
            for(String field:Arrays.asList("quantity","level"))if(!(m.opt(field) instanceof Integer))errors.put(prefix+"campo inteiro ausente: "+field);
            if(!contains(a.optJSONArray("operations"),op))errors.put(prefix+"operação não permitida.");
            if(!Arrays.asList("transfer","select","choose").contains(op)){missing.put(prefix+"operação sem simulador.");continue;}
            if("choose".equals(op)) {
                String choice=m.optString("choice_parameter");Object value=params.opt(choice);
                if(choice.isEmpty()||value==null||value==JSONObject.NULL||(value instanceof String&&((String)value).trim().isEmpty()))errors.put(prefix+"escolha parametrizada ausente.");
                if(value instanceof String) {
                    boolean concrete=entity(s,(String)value)!=null;
                    if(defs!=null)for(int j=0;j<defs.length();j++){JSONObject d=defs.getJSONObject(j);if(choice.equals(d.optString("name"))&&contains(d.optJSONArray("values"),(String)value))concrete=true;}
                    if(!concrete)errors.put(prefix+"escolha textual precisa ser alternativa explícita ou ID público, não intenção genérica.");
                }
                continue;
            }
            JSONObject c=entity(s,m.optString("component")),src=entity(s,m.optString("source")),dst=entity(s,m.optString("destination"));
            if(c==null){errors.put(prefix+"componente inexistente.");continue;}
            if(!Arrays.asList("component","resource","card").contains(c.optString("kind")))errors.put(prefix+"entidade não é componente/recurso/carta.");
            if(src==null){errors.put(prefix+"origem inexistente.");continue;}
            if("transfer".equals(op)&&(dst==null||!"position".equals(dst.optString("kind")))){errors.put(prefix+"destino inexistente ou não é uma posição.");continue;}
            if(!known(c)||!known(src)||("position".equals(src.optString("kind"))&&!src.optBoolean("occupancy_known"))){missing.put(prefix+"componente/origem não confirmado ou observado.");continue;}
            if(!c.optString("location").equals(m.optString("source")))errors.put(prefix+"componente não está na origem.");
            if(!contains(a.optJSONArray("sources"),m.optString("source")))errors.put(prefix+"origem não permitida.");
            int quantity=m.optInt("quantity",0);
            if("transfer".equals(op)||!contains(a.optJSONArray("operations"),"transfer"))total+=quantity;
            if(quantity<1||quantity>c.optInt("quantity",0))errors.put(prefix+"quantidade indisponível.");
            if(constraints!=null)for(int j=0;j<constraints.length();j++)if("same_group".equals(constraints.getJSONObject(j).optString("kind"))&&c.optString("group").isEmpty())missing.put(prefix+"confirme agrupamento do componente.");
            if(!c.optString("group").isEmpty()) {
                if(group==null)group=c.optString("group");
                JSONObject g=entity(s,c.optString("group"));if(!known(g))missing.put(prefix+"confirme agrupamento "+c.optString("group"));
            }
            if("select".equals(op)){
                if(constraints!=null)for(int j=0;j<constraints.length();j++)if("same_group".equals(constraints.getJSONObject(j).optString("kind"))&&group!=null&&!group.equals(c.optString("group")))errors.put(prefix+"componentes de grupos diferentes.");
                c.put("selected_quantity",quantity);continue;
            }
            if(dst==null||!"position".equals(dst.optString("kind"))){errors.put(prefix+"destino inexistente ou não é uma posição.");continue;}
            if(!known(dst)||!dst.optBoolean("occupancy_known")){missing.put(prefix+"ocupação do destino desconhecida: "+dst.optString("id"));continue;}
            if(!contains(a.optJSONArray("destinations"),dst.optString("id")))errors.put(prefix+"destino não permitido.");
            if(dst.optString("id").equals(src.optString("id")))errors.put(prefix+"origem igual ao destino.");
            JSONArray all=s.getJSONArray("entities");
            for(int j=0;j<all.length();j++){
                JSONObject other=all.getJSONObject(j);
                if(dst.optString("id").equals(other.optString("location"))&&!known(other))missing.put(prefix+"ocupação do destino desconhecida: componente ocluso ou inferido.");
            }
            int height=height(s,dst.optString("id")),level=m.optInt("level",-1);
            if(level!=height+1)errors.put(prefix+"nível deve ser "+(height+1)+" no estado sequencial.");
            if(quantity!=1)errors.put(prefix+"separe a transferência em movimentos unitários com ordem e nível explícitos.");
            if("position".equals(src.optString("kind"))&&c.optInt("level")!=height(s,src.optString("id")))errors.put(prefix+"apenas topo da pilha pode ser retirado pelo simulador atual.");
            if(constraints!=null)for(int j=0;j<constraints.length();j++) {
                JSONObject rule=constraints.getJSONObject(j);String kind=rule.optString("kind");
                switch(kind) {
                    case "max_height":if(level>rule.optInt("value",0))errors.put(prefix+"altura máxima excedida.");break;
                    case "empty_destination":if(height>0)errors.put(prefix+"destino deve estar vazio.");break;
                    case "adjacent":
                        JSONObject board=entity(s,src.optString("location"));String topology=board==null?"":board.optString("topology");
                        if(!known(board)||!Arrays.asList("hex_axial","graph").contains(topology)||("hex_axial".equals(topology)&&(!src.has("q")||!src.has("r")||!dst.has("q")||!dst.has("r")))||("graph".equals(topology)&&src.optJSONArray("neighbors")==null))
                            missing.put("Confirme a entidade de tabuleiro, coordenadas e conexões para verificar adjacência.");
                        else if(!adjacent(s,src,dst))errors.put(prefix+"destino não adjacente.");break;
                    case "selected_only":if(c.optInt("selected_quantity",0)<quantity)errors.put(prefix+"componente não selecionado.");break;
                    case "same_group":if(group!=null&&!group.equals(c.optString("group")))errors.put(prefix+"componentes de grupos diferentes.");break;
                    case "min_quantity":case "max_quantity":break;
                    default:missing.put("Restrição sem implementação: "+kind);
                }
            }
            if(errors.length()>0)continue;
            int remaining=c.optInt("quantity")-quantity;JSONObject moved=c;
            if(remaining>0) {
                moved=copy(c);moved.put("id","move:"+p.optString("id","draft")+":"+i);
                if(entity(s,moved.getString("id"))!=null){errors.put(prefix+"colisão de ID.");continue;}
                s.getJSONArray("entities").put(moved);c.put("quantity",remaining);c.put("selected_quantity",Math.max(0,c.optInt("selected_quantity")-quantity));
            }
            moved.put("quantity",quantity);moved.put("location",dst.getString("id"));moved.put("level",level);
            moved.put("group","");moved.put("selected_quantity",0);
        }
        if(constraints!=null)for(int i=0;i<constraints.length();i++){
            JSONObject rule=constraints.getJSONObject(i);String k=rule.optString("kind");
            if("min_quantity".equals(k)&&total<rule.optInt("value"))errors.put("Quantidade total abaixo do mínimo.");
            if("max_quantity".equals(k)&&total>rule.optInt("value"))errors.put("Quantidade total acima do máximo.");
            if(!Arrays.asList("max_height","empty_destination","adjacent","selected_only","same_group","min_quantity","max_quantity").contains(k))missing.put("Restrição sem implementação: "+k);
        }
        JSONArray effects=a.optJSONArray("effects");
        if(effects==null)missing.put("Efeitos da ação não definidos.");
        else for(int i=0;i<effects.length();i++){
            JSONObject effect=effects.getJSONObject(i);String field=effect.optString("field");
            if(!Arrays.asList("active_player","phase","substep","turn").contains(field)){missing.put("Efeito sem implementação: "+field);continue;}
            if("turn".equals(field)){try{int turn=Integer.parseInt(effect.getString("value"));if(turn<1)throw new Exception();s.put(field,turn);}catch(Exception e){errors.put("Turno inválido no efeito.");}}
            else s.put(field,effect.optString("value"));
        }
        return report(errors,missing,s);
    }
    static int height(JSONObject s,String position) {
        int height=0;JSONArray es=s.optJSONArray("entities");for(int i=0;i<es.length();i++){
            JSONObject e=es.optJSONObject(i);if(position.equals(e.optString("location"))&&e.optInt("quantity")>0)height=Math.max(height,e.optInt("level"));
        }return height;
    }
    static boolean adjacent(JSONObject s,JSONObject a,JSONObject b) {
        if(a==null||b==null||!a.optString("location").equals(b.optString("location")))return false;
        if(contains(a.optJSONArray("neighbors"),b.optString("id")))return true;
        JSONObject board=entity(s,a.optString("location"));if(board==null||!"hex_axial".equals(board.optString("topology")))return false;
        if(!a.has("q")||!a.has("r")||!b.has("q")||!b.has("r"))return false;
        int dq=a.optInt("q")-b.optInt("q"),dr=a.optInt("r")-b.optInt("r");return Math.max(Math.max(Math.abs(dq),Math.abs(dr)),Math.abs(dq+dr))==1;
    }
    static JSONArray steps(JSONObject p) throws Exception {
        JSONArray result=new JSONArray(),moves=p.getJSONArray("moves");
        for(int i=0;i<moves.length();i++) {
            JSONObject m=moves.getJSONObject(i);String op=m.optString("operation");
            result.put("choose".equals(op)?"Escolha "+m.optString("choice_parameter")+" = "+p.getJSONObject("parameters").opt(m.optString("choice_parameter")):
                "select".equals(op)?"Selecione "+m.optInt("quantity")+" × "+m.optString("component")+" da origem "+m.optString("source"):
                "Mova "+m.optInt("quantity")+" × "+m.optString("component")+" de "+m.optString("source")+" para "+m.optString("destination")+", nível "+m.optInt("level"));
        }return result;
    }
    static JSONObject proposal(JSONObject room,JSONObject data) throws Exception {
        JSONObject p=new JSONObject();
        for(String key:Arrays.asList("status","state_id","state_version","rules_revision","player","phase","substep","action","parameters","reason"))if(data.has(key))p.put(key,data.get(key));
        JSONArray input=data.optJSONArray("moves"),moves=new JSONArray();
        if(input!=null)for(int i=0;i<input.length();i++) {
            JSONObject original=input.optJSONObject(i),m=new JSONObject();
            if(original==null){moves.put(JSONObject.NULL);continue;}
            for(String key:Arrays.asList("operation","component","source","destination","quantity","level","choice_parameter"))if(original.has(key))m.put(key,original.get(key));
            moves.put(m);
        }
        p.put("moves",moves);p.put("id",UUID.randomUUID().toString());p.put("execution_status","proposed");
        JSONObject validation=validate(room,p);validation.remove("simulated");p.put("validation",validation);
        p.put("player_steps","invalid".equals(validation.optString("status"))?new JSONArray():steps(p));p.put("spoken",p.getJSONArray("player_steps").join(". ").replace("\"",""));
        p.put("reason",GameStore.clip(p.optString("reason"),1000));return p;
    }
}
