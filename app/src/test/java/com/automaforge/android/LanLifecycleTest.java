package com.automaforge.android;

import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** JVM tests invoke the existing HTTP dispatcher; no browser, instrumentation or network API. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LanLifecycleTest {
    LanServer server;String code;GameEngineTest fixture;JSONObject draft;
    @Before public void setup() throws Exception {
        fixture=new GameEngineTest();fixture.setup();
        GameStore store=new GameStore(RuntimeEnvironment.getApplication());
        JSONObject created=store.create("Synthetic game");code=created.getString("code");JSONObject room=store.get(code);
        for(java.util.Iterator<String> keys=fixture.room.keys();keys.hasNext();) {String key=keys.next();room.put(key,fixture.room.get(key));}
        room.put("rules_revision",2);store.save(room,false);
        server=new LanServer(RuntimeEnvironment.getApplication());
        draft=fixture.proposal(fixture.move("lot","offer","A",1),fixture.move("lot","offer","B",1));
    }
    @After public void close() {server.stop();}
    JSONObject call(String method,String suffix,JSONObject body) throws Exception {
        Class<?> reqClass=Class.forName("com.automaforge.android.LanServer$Request");
        Constructor<?> constructor=reqClass.getDeclaredConstructor();constructor.setAccessible(true);Object req=constructor.newInstance();
        for(String key:new String[]{"method","path","body"}) {
            Field f=reqClass.getDeclaredField(key);f.setAccessible(true);
            f.set(req,key.equals("method")?method:key.equals("path")?"/api/rooms/"+code+suffix:body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8));
        }
        Method dispatch=LanServer.class.getDeclaredMethod("dispatch",reqClass,Socket.class);dispatch.setAccessible(true);
        Object response;
        try {response=dispatch.invoke(server,req,new Socket());}catch(InvocationTargetException e){throw (Exception)e.getCause();}
        Field bytes=response.getClass().getDeclaredField("body");bytes.setAccessible(true);
        return new JSONObject(new String((byte[])bytes.get(response),StandardCharsets.UTF_8));
    }
    JSONObject propose() throws Exception {return call("POST","/automa/manual",draft);}
    JSONObject approve(JSONObject p) throws Exception {return call("POST","/automa/confirm",GameStore.obj("proposal_id",p.getString("id")));}
    @Test public void approvalDoesNotExecuteOrAdvanceTurn() throws Exception {
        JSONObject p=propose(),r=approve(p);
        assertEquals("approved",r.getJSONObject("proposal").getString("execution_status"));
        assertEquals(3,GameEngine.entity(r.getJSONObject("game_state"),"lot").getInt("quantity"));
        assertEquals(0,r.getJSONObject("game_state").getInt("version"));assertEquals(1,r.getInt("turn"));
    }
    @Test public void executionRequiresApproval() throws Exception {
        JSONObject p=propose();try{call("POST","/automa/executed",GameStore.obj("proposal_id",p.getString("id"),"completed_moves",2));fail();}
        catch(ApiException expected){assertEquals(409,expected.status);}
    }
    @Test public void partialExecutionCommitsOnlyPrefixAndInvalidatesRemainingPlan() throws Exception {
        JSONObject p=propose();approve(p);
        JSONObject r=call("POST","/automa/executed",GameStore.obj("proposal_id",p.getString("id"),"completed_moves",1)),s=r.getJSONObject("game_state");
        assertTrue(r.isNull("proposal"));assertEquals(1,s.getInt("version"));assertEquals(1,GameEngine.height(s,"A"));assertEquals(0,GameEngine.height(s,"B"));
        assertEquals(2,GameEngine.entity(s,"lot").getInt("quantity"));assertEquals("partial",r.getJSONArray("history").getJSONObject(0).getString("execution_status"));
    }
    @Test public void totalExecutionUsesSequentialStateAndPreservesTurnWithoutEffect() throws Exception {
        JSONObject p=propose();approve(p);
        JSONObject r=call("POST","/automa/executed",GameStore.obj("proposal_id",p.getString("id"),"completed_moves",2));
        assertEquals(1,GameEngine.height(r.getJSONObject("game_state"),"A"));assertEquals(1,GameEngine.height(r.getJSONObject("game_state"),"B"));assertEquals(1,r.getInt("turn"));
    }
    @Test public void correctionMakesPendingProposalUnapprovable() throws Exception {
        JSONObject p=propose(),s=call("GET","",null).getJSONObject("game_state");call("PUT","/state/structured",s);
        try{approve(p);fail();}catch(ApiException expected){assertEquals(422,expected.status);}
    }
    @Test public void genericManualIntentIsRejectedByExistingRoute() throws Exception {
        draft.put("moves",new JSONArray());try{propose();fail();}catch(ApiException expected){assertEquals(422,expected.status);}
    }
}
