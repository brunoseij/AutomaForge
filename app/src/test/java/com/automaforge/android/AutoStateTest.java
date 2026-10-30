package com.automaforge.android;
import com.automaforge.android.core.*;
import com.automaforge.android.domain.game.*;
import com.automaforge.android.data.ai.*;
import com.automaforge.android.data.game.*;
import com.automaforge.android.data.lan.*;
import com.automaforge.android.data.rulebook.*;

import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AutoStateTest {
    GameEngineTest base;JSONObject room,s;JSONArray visible;
    @Before public void setup() throws Exception {
        base=new GameEngineTest();base.setup();room=base.room;s=base.s;GameKnowledge.ensure(room);
        visible=new JSONArray(s.getJSONArray("entities").toString());
        s.put("entities",new JSONArray());s.put("phase","");s.put("substep","");s.put("active_player","");s.put("metadata_status","unknown");
        room.getJSONObject("state_model").put("reviewed",false);base.action.put("reviewed",false).put("validation_complete",false);
        AutoState.defaults(room);AutoState.assertAutomaTurn(room);
    }
    JSONObject meta(String field,String value) {return GameJson.obj("field",field,"value",value,"status","inferred","confidence",.96,"evidence","Derived from explicit rule stage and visible setup.");}
    JSONObject observation() throws Exception {
        JSONArray es=new JSONArray(visible.toString());for(int i=0;i<es.length();i++)es.getJSONObject(i).put("status","observed").put("confidence",.96).put("evidence","Synthetic visible entity and location");
        return GameJson.obj("entities",es,"facts",new JSONArray(),"metadata",new JSONArray().put(meta("phase","placement")).put(meta("substep","place")),"questions",new JSONArray(),"uncertainties",new JSONArray());
    }
    JSONObject p() throws Exception {return base.proposal(base.move("lot","offer","A",1)).put("state_version",s.getInt("version"));}
    void apply(JSONObject o) throws Exception {AutoState.applyObservation(room,o,1);s=GameEngine.state(room);base.s=s;}
    @Test public void identifiesAndProposesWithoutModelOrStateReviewGates() throws Exception {
        apply(observation());JSONObject p=p(),v=GameEngine.validate(room,p);
        assertEquals(0,v.getJSONArray("blockers").length());assertEquals("unverifiable",v.getString("status"));
        assertEquals(3,GameEngine.entity(s,"lot").getInt("quantity"));assertEquals("placement",s.getString("phase"));assertFalse(room.getJSONObject("state_model").getBoolean("reviewed"));
    }
    @Test public void inferredDataIsUsableWithEvidenceButNotFalselyConfirmed() throws Exception {
        JSONObject o=observation();GameKnowledge.named(o.getJSONArray("entities"),"id","lot").put("status","inferred");apply(o);
        JSONObject lot=GameEngine.entity(s,"lot");assertTrue(GameEngine.known(lot));assertEquals("inferred",lot.getString("status"));assertFalse(lot.getBoolean("human_locked"));
    }
    @Test public void weakReadingRemainsUnknownAndBlocksDecision() throws Exception {
        JSONObject o=observation();GameKnowledge.named(o.getJSONArray("entities"),"id","lot").put("confidence",.4);apply(o);
        assertEquals("unknown",GameEngine.entity(s,"lot").getString("status"));assertTrue(GameEngine.validate(room,p()).getJSONArray("blockers").length()>0);
    }
    @Test public void unknownMetadataIsNotInventedToFinishTheFlow() throws Exception {
        JSONObject o=observation();o.put("metadata",new JSONArray());apply(o);
        assertEquals("",s.getString("phase"));assertFalse(AutoState.metadataUsable(s));assertTrue(GameEngine.validate(room,p()).getJSONArray("blockers").length()>0);
    }
    @Test public void missingDetectionIsNotEmptyOrAvailableStock() throws Exception {
        apply(observation());JSONObject next=observation();JSONArray es=new JSONArray();for(int i=0;i<next.getJSONArray("entities").length();i++){JSONObject e=next.getJSONArray("entities").getJSONObject(i);if(!"lot".equals(e.getString("id")))es.put(e);}next.put("entities",es);apply(next);
        assertFalse(GameEngine.known(GameEngine.entity(s,"lot")));assertEquals(3,GameEngine.entity(s,"lot").getInt("quantity"));
    }
    @Test public void pooledLotKeepsIdentityWhenVisionRenamesIt() throws Exception {
        apply(observation());JSONObject next=observation();GameKnowledge.named(next.getJSONArray("entities"),"id","lot").put("id","new_visual_id");apply(next);
        assertNull(GameEngine.entity(s,"new_visual_id"));assertNotNull(GameEngine.entity(s,"lot"));assertEquals(6,s.getJSONArray("entities").length());
    }
    @Test public void humanCorrectionIsKeptAndConflictBlocksAffectedMove() throws Exception {
        s.put("entities",new JSONArray(visible.toString()));GameEngine.entity(s,"lot").put("quantity",5).put("human_locked",true);
        apply(observation());assertEquals(5,GameEngine.entity(s,"lot").getInt("quantity"));assertTrue(room.getJSONArray("auto_issues").length()>0);assertTrue(GameEngine.validate(room,p()).getJSONArray("blockers").length()>0);
    }
    @Test public void answeringOneFactDoesNotConfirmAllInferredMetadata() throws Exception {
        apply(observation());GameKnowledge.addQuestion(room,GameJson.obj("key","clarify","prompt","Which area?","scope","state","entity","","field","","input_type","text","options",new JSONArray(),"required",true),"board");
        JSONObject question=room.getJSONArray("questions").getJSONObject(0);GameKnowledge.answer(room,GameJson.obj("question_id",question.getString("id"),"value","left","state_version",s.getInt("version"),"rules_revision",room.optInt("rules_revision")));
        assertEquals("inferred",s.getString("metadata_status"));
    }
    @Test public void samePhotoDoesNotRepeatAConflictAlreadyResolvedByThePerson() throws Exception {
        s.put("entities",new JSONArray(visible.toString()));GameEngine.entity(s,"lot").put("quantity",5).put("human_locked",true);room.put("board_version",1);
        apply(observation());JSONObject question=null;JSONArray qs=room.getJSONArray("questions");for(int i=0;i<qs.length();i++)if("quantity".equals(qs.getJSONObject(i).optString("field")))question=qs.getJSONObject(i);
        assertNotNull(question);GameKnowledge.answer(room,GameJson.obj("question_id",question.getString("id"),"value",5,"state_version",s.getInt("version"),"rules_revision",room.optInt("rules_revision")));
        apply(observation());assertEquals(0,room.getJSONArray("auto_issues").length());assertEquals(5,GameEngine.entity(s,"lot").getInt("quantity"));
    }
    @Test public void malformedGraphDoesNotCommitHalfOfTheRecognizedState() throws Exception {
        JSONObject o=observation();GameKnowledge.named(o.getJSONArray("entities"),"id","lot").put("location","missing");
        JSONObject result=AutoState.applyObservation(room,o,1);assertFalse(result.getBoolean("applied"));assertEquals(0,s.getJSONArray("entities").length());
    }
    @Test public void activatingManualDefinitionsDoesNotClaimHumanOrRuleValidation() throws Exception {
        JSONObject m=GameJson.obj("summary","Game-specific","fields",new JSONArray(),"areas",new JSONArray(),"reviewed",true),a=GameEngine.copy(base.action).put("reviewed",true).put("validation_complete",true);
        room.put("actions",new JSONArray());AutoState.activateDefinition(room,GameJson.obj("state_model",m,"actions",new JSONArray().put(a),"rules_text","Explicit rules"),true);
        assertTrue(AutoState.modelReady(room));assertFalse(room.getJSONObject("state_model").getBoolean("reviewed"));assertFalse(room.getJSONArray("actions").getJSONObject(0).getBoolean("validation_complete"));assertEquals("Explicit rules",room.getString("rules_text"));
    }
    @Test public void semanticChangesDoNotMakeCurrentPhysicalPhotoStale() throws Exception {
        room.put("photo_physical_version",0);GameEngine.changed(room);assertTrue(AutoState.photoCurrent(room));s.put("physical_version",1);assertFalse(AutoState.photoCurrent(room));
    }
}
