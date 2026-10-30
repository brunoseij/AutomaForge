package com.automaforge.android;
import com.automaforge.android.core.*;
import com.automaforge.android.domain.game.*;
import com.automaforge.android.data.ai.*;
import com.automaforge.android.data.game.*;
import com.automaforge.android.data.lan.*;
import com.automaforge.android.data.rulebook.*;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import okhttp3.mockwebserver.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;

/** Exercises HTTP dispatcher + synthetic API outputs on the JVM; no Android UI or real model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AutomaticFlowTest {
    @Test public void oneRequestInfersSceneAndReturnsConcreteMovesWithoutIntermediateReviews() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();AiVisionTransportTest fixtures=new AiVisionTransportTest();LanLifecycleTest lan=new LanLifecycleTest();lan.setup();
        MockWebServer api=new MockWebServer();api.start();LanServer original=lan.server;
        try {
            GameStore storage=new GameStore(context);JSONObject r=storage.get(lan.code),s=r.getJSONObject("game_state");JSONArray before=new JSONArray(s.getJSONArray("entities").toString());
            s.put("entities",new JSONArray());s.put("metadata_status","unknown");s.put("phase","");s.put("substep","");s.put("active_player","");
            r.getJSONObject("state_model").put("reviewed",false);r.getJSONArray("actions").getJSONObject(0).put("reviewed",false).put("validation_complete",false);
            r.put("board_version",1);r.put("photo_state_version",0);r.put("photo_physical_version",0);storage.save(r,false);
            Bitmap image=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888);ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,95,out);image.recycle();Files.write(storage.boardPhoto(lan.code).toPath(),out.toByteArray());
            JSONObject layout=(JSONObject)fixtures.sample(fixtures.schema("board_layout")),scene=(JSONObject)fixtures.sample(fixtures.schema("board"));
            JSONObject es=fixtures.schema("board").getJSONObject("properties").getJSONObject("entities").getJSONObject("items");
            for(int i=0;i<before.length();i++) {
                JSONObject e=(JSONObject)fixtures.sample(es),source=before.getJSONObject(i);java.util.Iterator<String> keys=e.keys();while(keys.hasNext()){String key=keys.next();if(source.has(key))e.put(key,source.get(key));}
                e.put("status","observed").put("confidence",.96).put("evidence","Synthetic visible location");scene.getJSONArray("entities").put(e);
            }
            for(String field:new String[]{"phase","substep"})scene.getJSONArray("metadata").put(GameJson.obj("field",field,"value",field.equals("phase")?"placement":"place","status","inferred","confidence",.97,"evidence","Explicit current rule stage and visible components"));
            JSONObject proposal=lan.draft;proposal.put("state_version",2).put("parameters",new JSONArray());proposal.remove("id");
            for(JSONObject response:new JSONObject[]{layout,scene,proposal})api.enqueue(new MockResponse().setBody(GameJson.obj("status","completed","output",new JSONArray().put(GameJson.obj("content",new JSONArray().put(GameJson.obj("type","output_text","text",response.toString()))))).toString()));
            lan.server=new LanServer(context,new AiClient(context,api.url("/v1/responses").toString(),()->"fake-only"));
            JSONObject result=lan.call("POST","/automa/assist",new JSONObject());
            assertEquals("proposed",result.getString("status"));assertEquals("unverifiable",result.getJSONObject("validation").getString("status"));assertEquals(2,result.getJSONArray("moves").length());assertEquals(3,api.getRequestCount());
            JSONObject room=lan.call("GET","",null);assertEquals("inferred",room.getJSONObject("game_state").getString("metadata_status"));assertFalse(room.getJSONObject("state_model").getBoolean("reviewed"));assertEquals("done",room.getJSONObject("assist_task").getString("status"));
        } finally {lan.close();original.stop();api.shutdown();}
    }
}
