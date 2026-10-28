package com.automaforge.android;

import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Pure JVM/Robolectric: synthetic public state, no camera, device, emulator or remote API. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class GameEngineTest {
    JSONObject room,s,action;
    JSONObject entity(String id,String kind,String location,int qty,int level) {
        return GameStore.obj("id",id,"kind",kind,"type",kind,"location",location,"group","","quantity",qty,"level",level,
            "status","confirmed","source","human","visibility","public","identity","pooled","occupancy_known",true);
    }
    @Before public void setup() throws Exception {
        room=GameStore.obj("phase","placement","turn",1,"rules_revision",2,"proposal",JSONObject.NULL);
        s=GameEngine.state(room);s.put("active_player","automa");s.put("phase","placement");s.put("substep","place");s.put("metadata_status","confirmed");
        s.put("entities",new JSONArray().put(entity("board","board","",0,0).put("topology","hex_axial"))
            .put(entity("offer","group","",0,0)).put(entity("A","position","board",0,0).put("q",0).put("r",0))
            .put(entity("B","position","board",0,0).put("q",1).put("r",0))
            .put(entity("C","position","board",0,0).put("q",1).put("r",1))
            .put(entity("lot","component","offer",3,0).put("group","offer")));
        action=GameStore.obj("name","Place","reviewed",true,"validation_complete",true,"phase","placement","player","automa",
            "parameters",new JSONArray(),"sources",new JSONArray().put("offer").put("A").put("B"),"destinations",new JSONArray().put("A").put("B").put("C"),
            "operations",new JSONArray().put("transfer").put("select").put("choose"),"min_moves",1,"max_moves",10,
            "required_state",new JSONArray().put("offer"),"constraints",new JSONArray().put(GameStore.obj("kind","max_height","value",3)),"effects",new JSONArray());
        room.put("actions",new JSONArray().put(action));
    }
    JSONObject move(String lot,String src,String dst,int level) {
        return GameStore.obj("operation","transfer","component",lot,"source",src,"destination",dst,"quantity",1,"level",level,"choice_parameter","");
    }
    JSONObject proposal(JSONObject... moves) throws Exception {
        JSONArray ms=new JSONArray();for(JSONObject m:moves)ms.put(m);
        return GameStore.obj("id","test","status","proposed","state_id",s.getString("id"),"state_version",s.getInt("version"),"rules_revision",2,
            "player","automa","phase","placement","substep","place","action","Place","parameters",new JSONObject(),"moves",ms,"reason","","needed_data",new JSONArray());
    }
    String status(JSONObject p) throws Exception {return GameEngine.validate(room,p).getString("status");}
    @Test public void concreteSequentialPlacementsAndCopyIsolation() throws Exception {
        JSONObject p=proposal(move("lot","offer","A",1),move("lot","offer","A",2),move("lot","offer","B",1));
        JSONObject result=GameEngine.validate(room,p);assertEquals("valid",result.getString("status"));
        assertEquals(1,GameEngine.entity(result.getJSONObject("simulated"),"lot").getInt("quantity")); // final unit moved with stable original lot ID
        assertEquals(3,GameEngine.entity(s,"lot").getInt("quantity"));assertEquals(0,GameEngine.height(s,"A"));
        assertEquals(2,GameEngine.height(result.getJSONObject("simulated"),"A"));
    }
    @Test public void sequentialDestinationMustUseUpdatedHeight() throws Exception {
        assertEquals("invalid",status(proposal(move("lot","offer","A",1),move("lot","offer","A",1))));
    }
    @Test public void laterMoveCanUseNewLocation() throws Exception {
        GameEngine.entity(s,"lot").put("quantity",1);
        assertEquals("valid",status(proposal(move("lot","offer","A",1),move("lot","A","B",1))));
    }
    @Test public void genericIntentAndMissingParametersAreRejected() throws Exception {
        assertEquals("invalid",status(proposal()));
        action.getJSONArray("parameters").put(GameStore.obj("name","choice","type","string","required",true,"values",new JSONArray().put("public")));
        assertEquals("invalid",status(proposal(move("lot","offer","A",1))));
    }
    @Test public void nonexistentPieceAndDestinationAreRejected() throws Exception {
        assertEquals("invalid",status(proposal(move("ghost","offer","A",1))));
        assertEquals("invalid",status(proposal(move("lot","offer","void",1))));
    }
    @Test public void staleStateAndStaleRulesAreRejected() throws Exception {
        JSONObject p=proposal(move("lot","offer","A",1));GameEngine.changed(room);assertEquals("invalid",status(p));
        p=proposal(move("lot","offer","A",1));room.put("rules_revision",3);assertEquals("invalid",status(p));
    }
    @Test public void incompleteOfferAndUnknownOccupancyAreBlockers() throws Exception {
        GameEngine.entity(s,"offer").put("status","unknown");JSONObject result=GameEngine.validate(room,proposal(move("lot","offer","A",1)));
        assertEquals("unverifiable",result.getString("status"));assertTrue(result.getJSONArray("blockers").length()>0);
        GameEngine.entity(s,"offer").put("status","confirmed");GameEngine.entity(s,"A").put("occupancy_known",false);
        assertTrue(GameEngine.validate(room,proposal(move("lot","offer","A",1))).getJSONArray("blockers").length()>0);
    }
    @Test public void inferredPieceCannotBecomeConfirmedBySimulation() throws Exception {
        GameEngine.entity(s,"lot").put("status","inferred");
        assertEquals("unverifiable",status(proposal(move("lot","offer","A",1))));
    }
    @Test public void unsupportedRuleIsNotValidated() throws Exception {
        action.getJSONArray("constraints").put(GameStore.obj("kind","custom_unimplemented","value",0));
        assertEquals("unverifiable",status(proposal(move("lot","offer","A",1))));
        action.put("validation_complete",false);assertEquals("unverifiable",status(proposal(move("lot","offer","A",1))));
    }
    @Test public void hexAdjacencyIsAxialNotSquare() throws Exception {
        assertTrue(GameEngine.adjacent(s,GameEngine.entity(s,"A"),GameEngine.entity(s,"B")));
        assertFalse(GameEngine.adjacent(s,GameEngine.entity(s,"A"),GameEngine.entity(s,"C")));
    }
    @Test public void correctionIsLockedAndObservationIsNotAutomaticState() throws Exception {
        GameEngine.correct(room,GameEngine.copy(s));s=GameEngine.state(room);
        JSONObject original=GameEngine.copy(GameEngine.entity(s,"lot"));
        JSONObject candidate=GameEngine.copy(original).put("quantity",9).put("status","observed");
        JSONObject o=GameEngine.observation(room,GameStore.obj("entities",new JSONArray().put(candidate),"sufficient",false),7);
        assertTrue(o.getJSONArray("conflicts").length()>0);assertEquals(3,GameEngine.entity(s,"lot").getInt("quantity"));
        assertTrue(GameEngine.entity(s,"lot").getBoolean("human_locked"));
    }
    @Test public void duplicateIndistinguishableLotsAreNotNewPieces() throws Exception {
        s.getJSONArray("entities").put(GameEngine.copy(GameEngine.entity(s,"lot")).put("id","duplicate"));
        try {GameEngine.checkState(s);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
    }
    @Test public void staleManualCorrectionCannotOverwriteState() throws Exception {
        JSONObject before=GameEngine.copy(s);GameEngine.changed(room);
        try {GameEngine.correct(room,before);fail();}catch(ApiException expected){assertEquals(409,expected.status);}
    }
    @Test public void wrongPhasePlayerAndNonIntegerFieldsAreRejected() throws Exception {
        assertEquals("invalid",status(proposal(move("lot","offer","A",1)).put("player","other")));
        assertEquals("invalid",status(proposal(move("lot","offer","A",1)).put("phase","other")));
        assertEquals("invalid",status(proposal(move("lot","offer","A",1).put("quantity","1"))));
    }
    @Test public void selectedOnlyAndGroupConstraintsApplySequentially() throws Exception {
        action.getJSONArray("constraints").put(GameStore.obj("kind","selected_only","value",0));
        assertEquals("invalid",status(proposal(move("lot","offer","A",1))));
        JSONObject select=move("lot","offer","",0).put("operation","select");
        assertEquals("valid",status(proposal(select,move("lot","offer","A",1))));
    }
    @Test public void noPrivateDataCanEnterPublicState() throws Exception {
        GameEngine.entity(s,"lot").put("visibility","private");
        try {GameEngine.correct(room,s);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
    }
    @Test public void genericChoiceIsNotConcreteEvenWithTextParameter() throws Exception {
        action.getJSONArray("parameters").put(GameStore.obj("name","choice","type","string","required",true,"values",new JSONArray()));
        JSONObject choice=move("","","",0).put("operation","choose").put("choice_parameter","choice");
        JSONObject p=proposal(choice).put("parameters",GameStore.obj("choice","escolher a melhor opção"));assertEquals("invalid",status(p));
        action.getJSONArray("parameters").getJSONObject(0).getJSONArray("values").put("explicit_public_choice");
        p.put("parameters",GameStore.obj("choice","explicit_public_choice"));assertEquals("valid",status(p));
    }
    @Test public void malformedLocalContractReturnsInvalidInsteadOfThrowing() throws Exception {
        JSONObject p=proposal().put("moves",new JSONArray().put("generic instruction"));
        assertEquals("invalid",status(p));assertEquals(0,GameEngine.proposal(room,p).getJSONArray("player_steps").length());
    }
    @Test public void responseSchemaEnforcesEnumsAndParameterTypesLocally() throws Exception {
        JSONObject schema;
        try(java.io.InputStream in=RuntimeEnvironment.getApplication().getAssets().open("schemas/proposal.json")) {
            schema=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        JSONObject p=proposal(move("lot","offer","A",1));p.remove("id");p.put("parameters",new JSONArray());
        RuleJsonValidator.INSTANCE.validate(p,schema);
        p.put("status","generic_intent");
        try{RuleJsonValidator.INSTANCE.validate(p,schema);fail();}catch(IllegalArgumentException expected){}
    }

    @Test public void legacyRoomLoadsWithoutInventingOccupancyAndPersists() throws Exception {
        GameStore store=new GameStore(RuntimeEnvironment.getApplication());JSONObject created=store.create("legacy");
        JSONObject old=store.get(created.getString("code"));old.remove("game_state");old.put("proposal",GameStore.obj("action","old generic"));store.save(old,false);
        GameStore reloaded=new GameStore(RuntimeEnvironment.getApplication());JSONObject loaded=reloaded.get(created.getString("code"));
        assertEquals(0,GameEngine.state(loaded).getJSONArray("entities").length());assertTrue(loaded.isNull("proposal"));
        assertEquals("unknown",GameEngine.state(loaded).getString("metadata_status"));
    }
}
