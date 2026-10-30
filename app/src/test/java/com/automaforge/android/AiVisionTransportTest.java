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
import java.io.*;
import java.util.*;

/** Synthetic API responses over localhost, never a real vision/model evaluation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AiVisionTransportTest {
    MockWebServer server;AiClient ai;GameEngineTest fixture;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start();ai=new AiClient(RuntimeEnvironment.getApplication(),server.url("/v1/responses").toString(),()->"test-only");
        fixture=new GameEngineTest();fixture.setup();GameKnowledge.ensure(fixture.room);
    }
    @After public void close() throws Exception {server.shutdown();}
    Object sample(JSONObject schema) throws Exception {
        if(schema.has("anyOf"))return sample(schema.getJSONArray("anyOf").getJSONObject(0));
        if(schema.has("enum"))return schema.getJSONArray("enum").get(0);
        switch(schema.getString("type")) {
            case "object":JSONObject o=new JSONObject(),properties=schema.getJSONObject("properties");for(Iterator<String> keys=properties.keys();keys.hasNext();){String k=keys.next();o.put(k,sample(properties.getJSONObject(k)));}return o;
            case "array":return new JSONArray();case "boolean":return false;case "integer":return 0;case "number":return 0.0;case "null":return JSONObject.NULL;default:return "";
        }
    }
    JSONObject schema(String name) throws Exception {
        try(InputStream in=RuntimeEnvironment.getApplication().getAssets().open("schemas/"+name+".json")){return new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    }
    void enqueue(JSONObject value){server.enqueue(new MockResponse().setBody(GameJson.obj("status","completed","output",new JSONArray().put(GameJson.obj("content",new JSONArray().put(GameJson.obj("type","output_text","text",value.toString()))))).toString()));}
    JSONArray content(String request) throws Exception {return new JSONObject(request).getJSONArray("input").getJSONObject(0).getJSONArray("content");}
    long count(JSONArray content,String type) throws Exception {long result=0;for(int i=0;i<content.length();i++)if(type.equals(content.getJSONObject(i).optString("type")))result++;return result;}
    @Test public void overviewCropsManualReferencesAndAnswersReachBothRecognitionPasses() throws Exception {
        JSONObject layout=(JSONObject)sample(schema("board_layout"));layout.getJSONArray("areas").put(GameJson.obj("id","detected_board","label","Visible area","area_type","personal","owner","","region",new JSONArray().put(.1).put(.1).put(.7).put(.7),"confidence","medium"));enqueue(layout);
        JSONObject observed=(JSONObject)sample(schema("board"));observed.getJSONArray("facts").put(GameJson.obj("key","custom_counter","subject","game","value",7,"status","observed","confidence",.95,"evidence","Synthetic visible counter","region",new JSONArray()));enqueue(observed);
        Bitmap b=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888);ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,95,bytes);b.recycle();
        File dir=java.nio.file.Files.createTempDirectory("manual-ref").toFile(),ref=new File(dir,"reference-p1.png");try {
            ref.createNewFile();java.nio.file.Files.write(ref.toPath(),bytes.toByteArray());
            fixture.room.getJSONArray("clarifications").put(GameJson.obj("question","Who owns this area?","value","automa","status","confirmed"));
            JSONObject result=ai.board(fixture.room,bytes.toByteArray(),Collections.singletonList(ref));
            assertEquals(2,result.getInt("analysis_passes"));assertEquals(1,result.getInt("crop_count"));assertEquals(7,result.getJSONArray("facts").getJSONObject(0).getInt("value"));
            String first=server.takeRequest().getBody().readUtf8(),second=server.takeRequest().getBody().readUtf8();
            assertEquals(2,count(content(first),"input_image"));assertEquals(3,count(content(second),"input_image"));
            assertTrue(first.contains("MANUAL_REFERENCE"));assertTrue(first.contains("CURRENT_PHOTO"));assertTrue(second.contains("crop:detected_board"));assertTrue(first.contains("human_answers"));assertTrue(second.contains("Who owns this area?"));
        } finally {ref.delete();dir.delete();}
    }
    @Test public void customModelIsSentAndIncompatibilityDoesNotSwitchIt() throws Exception {
        new AiSettings(RuntimeEnvironment.getApplication()).saveModel("my-vision-model");
        enqueue((JSONObject)sample(schema("board_layout")));ai.ask("Synthetic request","board_layout",null);
        assertEquals("my-vision-model",new JSONObject(server.takeRequest().getBody().readUtf8()).getString("model"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"unsupported_model\"}}"));
        try{ai.ask("Synthetic request","board_layout",null);fail();}catch(ApiException expected){assertTrue(expected.getMessage().contains("não trocou de modelo"));}
        assertEquals("my-vision-model",new AiSettings(RuntimeEnvironment.getApplication()).model());assertEquals(2,server.getRequestCount());
    }
    @Test public void regionValidationRejectsImpossibleAndMissingCoordinates() throws Exception {
        assertFalse(BoardImages.validRegion(new JSONArray().put(.8).put(.8).put(.7).put(.7)));
        assertFalse(BoardImages.validRegion(new JSONArray()));
        assertTrue(BoardImages.validRegion(new JSONArray().put(.1).put(.1).put(.5).put(.5)));
    }
    @Test public void selectedReasoningIsSentOnResponsesAndDoesNotLeakIntoNonReasoningRequests() throws Exception {
        AiSettings settings=new AiSettings(RuntimeEnvironment.getApplication());settings.save("gpt-5.4","high");
        enqueue((JSONObject)sample(schema("board_layout")));ai.ask("Synthetic request","board_layout",null);
        JSONObject request=new JSONObject(server.takeRequest().getBody().readUtf8());assertEquals("gpt-5.4",request.getString("model"));assertEquals("high",request.getJSONObject("reasoning").getString("effort"));
        settings.save("gpt-4.1-mini","default");enqueue((JSONObject)sample(schema("board_layout")));ai.ask("Synthetic request","board_layout",null);
        assertFalse(new JSONObject(server.takeRequest().getBody().readUtf8()).has("reasoning"));
    }
}
