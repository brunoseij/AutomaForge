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
public class GameKnowledgeTest {
    GameEngineTest base;JSONObject room,s;
    JSONObject field(String key,String type,String scope,String visibility) {
        return GameJson.obj("key",key,"label",key,"description","Public state from this rulebook","type",type,"scope",scope,"visibility",visibility,
            "options",new JSONArray(),"observable",true,"required_for_actions",new JSONArray().put("Place"),"required_phases",new JSONArray(),"rule_refs",new JSONArray(),"changes_with_actions",new JSONArray().put("Place"));
    }
    JSONObject area(String key,String scope) {return GameJson.obj("key",key,"label",key,"scope",scope,"kind","board","topology","hex_axial","visual_cues","Visual layout from manual","required_for_actions",new JSONArray().put("Place"),"rule_refs",new JSONArray(),"visual_sources",new JSONArray());}
    @Before public void setup() throws Exception {
        base=new GameEngineTest();base.setup();room=base.room;s=base.s;GameKnowledge.ensure(room);
        room.put("state_model",GameJson.obj("reviewed",true,"summary","Dynamic","areas",new JSONArray(),"fields",new JSONArray().put(field("stock","integer","game","public")),"visual_sources",new JSONArray()));
    }
    JSONObject q(String key,String entity,String field) {return GameJson.obj("key",key,"prompt","Confirm "+key,"scope","state","entity",entity,"field",field,"input_type","text","options",new JSONArray(),"required",true);}
    JSONObject answer(String id,Object value) throws Exception {return GameJson.obj("question_id",id,"value",value,"state_version",s.getInt("version"),"rules_revision",room.optInt("rules_revision"));}
    @Test public void requirementsChangeWithEachGameModel() throws Exception {
        assertTrue(GameKnowledge.blockers(room,s,"Place").length()>0);
        GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","game","value",3),true);assertEquals(0,GameKnowledge.blockers(room,s,"Place").length());
        JSONObject other=field("current_season","enum","game","public");other.getJSONArray("options").put("winter").put("summer");
        room.getJSONObject("state_model").put("fields",new JSONArray().put(other));assertTrue(GameKnowledge.blockers(room,s,"Place").length()>0);
        GameKnowledge.putFact(room,s,GameJson.obj("key","current_season","subject","game","value","winter"),true);assertEquals(0,GameKnowledge.blockers(room,s,"Place").length());
    }
    @Test public void missingVariableBlocksConcreteProposal() throws Exception {
        assertTrue(GameEngine.validate(room,base.proposal(base.move("lot","offer","A",1))).getJSONArray("blockers").length()>0);
    }
    @Test public void privateVariableCannotBeAnsweredPublicly() throws Exception {
        room.getJSONObject("state_model").getJSONArray("fields").getJSONObject(0).put("visibility","private");
        try{GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","game","value",3),true);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
        GameKnowledge.addQuestion(room,q("private","game","stock"),"board");assertEquals(0,room.getJSONArray("questions").length());
    }
    @Test public void humanFactSurvivesNewVisualCandidate() throws Exception {
        GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","game","value",3),true);
        GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","game","value",9,"status","observed"),false);
        assertEquals(3,GameKnowledge.fact(s,"stock","game").getInt("value"));
    }
    @Test public void wrongTypeAndOutOfScopeValueAreRejected() throws Exception {
        try{GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","game","value","3"),true);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
        try{GameKnowledge.putFact(room,s,GameJson.obj("key","stock","subject","other","value",3),true);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
    }
    @Test public void unansweredAndUnknownAreNotInventedFacts() throws Exception {
        GameKnowledge.refreshQuestions(room);JSONObject q=room.getJSONArray("questions").getJSONObject(0);
        GameKnowledge.answer(room,answer(q.getString("id"),JSONObject.NULL).put("unknown",true));
        assertNull(GameKnowledge.fact(s,"stock","game"));assertEquals("unknown",q.getString("status"));assertTrue(GameKnowledge.blockers(room,s,"Place").length()>0);
    }
    @Test public void answerUpdatesTypedFactAndIsIncludedInInferenceContext() throws Exception {
        GameKnowledge.refreshQuestions(room);JSONObject q=room.getJSONArray("questions").getJSONObject(0);
        GameKnowledge.answer(room,answer(q.getString("id"),4));
        assertEquals(4,GameKnowledge.fact(s,"stock","game").getInt("value"));assertEquals("human",GameKnowledge.fact(s,"stock","game").getString("source"));
        assertEquals(1,GameKnowledge.context(room).getJSONArray("human_answers").length());assertEquals(0,GameKnowledge.blockers(room,s,"Place").length());
    }
    @Test public void clarificationCanIdentifyTheAutomasBoard() throws Exception {
        room.getJSONObject("state_model").getJSONArray("areas").put(area("personal","player"));
        JSONObject candidate=base.entity("new_board","board","",0,0).put("owner","").put("area_type","personal").put("status","observed");
        JSONObject o=GameJson.obj("entities",new JSONArray().put(candidate),"questions",new JSONArray(),"uncertainties",new JSONArray(),"state_version",s.getInt("version"),"photo_version",0);
        room.put("board_observation",o);GameKnowledge.questions(room,o,"board");JSONObject q=room.getJSONArray("questions").getJSONObject(0);
        GameKnowledge.answer(room,answer(q.getString("id"),"automa"));
        assertEquals("automa",GameEngine.entity(s,"new_board").getString("owner"));assertTrue(GameEngine.entity(s,"new_board").getBoolean("human_locked"));
    }
    @Test public void semanticAreaSelectorsResolveRealPositionsAndRespectOwner() throws Exception {
        room.getJSONObject("state_model").getJSONArray("areas").put(area("personal","player"));GameEngine.entity(s,"board").put("area_type","personal").put("owner","automa");
        JSONArray selectors=new JSONArray().put("area:personal");assertTrue(GameKnowledge.allows(room,s,selectors,"A"));
        GameEngine.entity(s,"board").put("owner","other");assertFalse(GameKnowledge.allows(room,s,selectors,"A"));
    }
    @Test public void changedVariableReopensAnEarlierAnsweredQuestion() throws Exception {
        GameKnowledge.refreshQuestions(room);JSONObject question=room.getJSONArray("questions").getJSONObject(0);
        GameKnowledge.answer(room,answer(question.getString("id"),4));assertEquals("answered",question.getString("status"));
        GameKnowledge.fact(s,"stock","game").put("status","unknown");GameEngine.changed(room);GameKnowledge.refreshQuestions(room);
        assertEquals("open",question.getString("status"));assertTrue(question.isNull("answer"));
    }
    @Test public void staleAnswersAreRejected() throws Exception {
        GameKnowledge.addQuestion(room,q("count","game","stock"),"board");JSONObject q=room.getJSONArray("questions").getJSONObject(0);JSONObject body=answer(q.getString("id"),3);GameEngine.changed(room);
        try{GameKnowledge.answer(room,body);fail();}catch(ApiException expected){assertEquals(409,expected.status);}
    }
    @Test public void answeringOneQuestionKeepsOtherCurrentQuestionsAnswerable() throws Exception {
        GameKnowledge.addQuestion(room,q("a","",""),"board");GameKnowledge.addQuestion(room,q("b","",""),"board");JSONArray qs=room.getJSONArray("questions");
        GameKnowledge.answer(room,answer(qs.getJSONObject(0).getString("id"),"first"));GameKnowledge.answer(room,answer(qs.getJSONObject(1).getString("id"),"second"));
        assertEquals(2,room.getJSONArray("clarifications").length());
    }
    @Test public void scopeUsesActivePlayerAndDoesNotAskEveryPlayersSecrets() throws Exception {
        room.getJSONObject("state_model").getJSONArray("fields").getJSONObject(0).put("scope","player");
        assertEquals(java.util.Arrays.asList("automa"),GameKnowledge.subjects(room,s,room.getJSONObject("state_model").getJSONArray("fields").getJSONObject(0)));
    }
}
