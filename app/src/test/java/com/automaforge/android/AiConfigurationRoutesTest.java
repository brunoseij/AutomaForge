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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Calls the LAN dispatcher in the JVM; no UI, instrumentation or remote API. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AiConfigurationRoutesTest {
    LanServer server;
    @Before public void setup(){server=new LanServer(RuntimeEnvironment.getApplication());}
    @After public void close(){server.stop();}
    JSONObject call(String method,JSONObject body,boolean local) throws Exception {
        Class<?> reqClass=Class.forName("com.automaforge.android.data.lan.LanServer$Request");Constructor<?> ctor=reqClass.getDeclaredConstructor();ctor.setAccessible(true);Object req=ctor.newInstance();
        for(String key:new String[]{"method","path","body"}) {Field f=reqClass.getDeclaredField(key);f.setAccessible(true);f.set(req,key.equals("method")?method:key.equals("path")?"/api/ai/settings":body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8));}
        Socket caller=new Socket(){@Override public InetAddress getInetAddress(){try{return InetAddress.getByAddress(local?new byte[]{127,0,0,1}:new byte[]{(byte)192,(byte)168,1,20});}catch(Exception e){throw new AssertionError(e);}}};
        Method dispatch=LanServer.class.getDeclaredMethod("dispatch",reqClass,Socket.class);dispatch.setAccessible(true);Object response;
        try{response=dispatch.invoke(server,req,caller);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
        Field bytes=response.getClass().getDeclaredField("body");bytes.setAccessible(true);return new JSONObject(new String((byte[])bytes.get(response),StandardCharsets.UTF_8));
    }
    @Test public void localSelectionRoundTripsBothValuesAndTheSharedMenuOptions() throws Exception {
        call("PUT",GameJson.obj("model","gpt-5.4","reasoning_effort","high"),true);JSONObject cfg=call("GET",null,true);
        assertEquals("gpt-5.4",cfg.getString("model"));assertEquals("high",cfg.getString("reasoning_effort"));assertTrue(cfg.getBoolean("can_configure"));assertTrue(cfg.getJSONArray("model_options").length()>1);assertFalse(cfg.has("api_key"));
    }
    @Test public void incompatiblePairIsRejectedWithoutOverwritingSavedSelection() throws Exception {
        call("PUT",GameJson.obj("model","gpt-5.4","reasoning_effort","high"),true);
        try{call("PUT",GameJson.obj("model","gpt-4.1-mini","reasoning_effort","high"),true);fail();}catch(ApiException e){assertEquals(422,e.status);}
        assertEquals("gpt-5.4",call("GET",null,true).getString("model"));assertEquals("high",call("GET",null,true).getString("reasoning_effort"));
    }
    @Test public void lanReadersCannotChangeGlobalSelectionOrPassAnApiKey() throws Exception {
        assertFalse(call("GET",null,false).getBoolean("can_configure"));
        try{call("PUT",GameJson.obj("model","gpt-5.4","reasoning_effort","low"),false);fail();}catch(ApiException e){assertEquals(403,e.status);}
        try{call("PUT",GameJson.obj("model","gpt-5.4","reasoning_effort","low","api_key","not-a-real-key"),true);fail();}catch(ApiException e){assertEquals(422,e.status);}
    }
}
