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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AiSettingsTest {
    @Test public void arbitraryModelIsSharedByVisionAndManualAndSurvivesRecreation() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();AiSettings settings=new AiSettings(context);settings.saveModel("custom-vision-model-v2");
        assertEquals("custom-vision-model-v2",new AiSettings(context).model());assertEquals("custom-vision-model-v2",new AiClient(context).model());
        OpenAiRulebookGateway gateway=new OpenAiRulebookGateway(context);assertEquals("custom-vision-model-v2",gateway.model());
    }
    @Test public void choosingAnotherModelChangesRevisionAndDoesNotChangeTheCredential() {
        AiSettings settings=new AiSettings(RuntimeEnvironment.getApplication());int before=settings.revision();settings.saveModel("other-compatible-model");assertEquals(before+1,settings.revision());settings.saveModel("other-compatible-model");assertEquals(before+1,settings.revision());
    }
    @Test public void invalidIdentifiersDoNotOverwriteTheCurrentSelection() {
        AiSettings settings=new AiSettings(RuntimeEnvironment.getApplication());String prior=settings.model();
        for(String bad:new String[]{"","bad model","https://example.com/model","a\nmodel"})try{settings.saveModel(bad);fail();}catch(IllegalArgumentException expected){assertEquals(prior,settings.model());}
        assertTrue(AiSettings.validModel("ft:custom-model:org:snapshot"));
    }
    @Test public void oneAnalysisPinsItsModelInsteadOfSwitchingBetweenSteps() {
        android.content.Context context=RuntimeEnvironment.getApplication();AiSettings settings=new AiSettings(context);settings.saveModel("first-model");AiClient session=new AiClient(context).snapshot();settings.saveModel("second-model");assertEquals("first-model",session.model());assertEquals("second-model",new AiClient(context).model());
    }
    @Test public void publicSettingsNeverContainAnApiKey() {
        JSONObject data=new AiSettings(RuntimeEnvironment.getApplication()).publicSettings();assertFalse(data.has("key"));assertFalse(data.has("api_key"));assertTrue(AiSettings.errorMessage(400).contains("não trocou de modelo"));
    }
    @Test public void upgradingFromModelOnlyPreferencesPreservesModelAndOmitsEffort() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("private_settings",0).edit().putString("model","gpt-4.1-mini").remove("reasoning_effort").commit();
        AiSettings settings=new AiSettings(context);assertEquals("gpt-4.1-mini",settings.model());assertEquals("default",settings.reasoningEffort());
        assertFalse(settings.snapshot().applyTo(new JSONObject()).has("reasoning"));
    }
    @Test public void modelAndReasoningPersistTogetherAndAnEffortOnlyChangeInvalidatesRevision() {
        android.content.Context context=RuntimeEnvironment.getApplication();AiSettings settings=new AiSettings(context);
        settings.save("gpt-5.4","low");int revision=settings.revision();settings.save("gpt-5.4","high");
        assertEquals(revision+1,settings.revision());assertEquals("high",new AiSettings(context).reasoningEffort());
        settings.save("gpt-5.4","high");assertEquals(revision+1,settings.revision());
    }
    @Test public void invalidEffortOrUnsupportedPairLeavesTheEntireSelectionUnchanged() {
        AiSettings settings=new AiSettings(RuntimeEnvironment.getApplication());settings.save("gpt-5.4","high");int before=settings.revision();
        for(String[] pair:new String[][]{{"gpt-4.1-mini","high"},{"gpt-6-astra","none"},{"gpt-6.1-sol","minimal"},{"gpt-5.4","ultra"},{"unknown-model","high"}}) {
            try{settings.save(pair[0],pair[1]);fail();}catch(IllegalArgumentException expected){}
            assertEquals("gpt-5.4",settings.model());assertEquals("high",settings.reasoningEffort());assertEquals(before,settings.revision());
        }
    }
    @Test public void oneAnalysisPinsEffortForVisionAndForTheManualGateway() {
        android.content.Context context=RuntimeEnvironment.getApplication();AiSettings settings=new AiSettings(context);settings.save("gpt-5.4","low");
        AiClient vision=new AiClient(context).snapshot();OpenAiRulebookGateway manual=new OpenAiRulebookGateway(context);
        settings.save("gpt-5.4","high");assertEquals("low",vision.reasoningEffort());assertEquals("low",manual.reasoningEffort());assertEquals("high",new AiClient(context).reasoningEffort());
    }
    @Test public void cataloguePreservesSavedCustomModelAndDescribesSupportedEfforts() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();AiSettings settings=new AiSettings(context);settings.saveModel("saved-custom-model");
        JSONArray options=settings.publicSettings().getJSONArray("model_options");boolean custom=false;
        for(int i=0;i<options.length();i++) {
            JSONObject option=options.getJSONObject(i);JSONArray efforts=option.getJSONArray("reasoning_options");
            assertTrue(efforts.length()>0);assertEquals("default",efforts.getJSONObject(0).getString("value"));
            for(int j=0;j<efforts.length();j++)assertTrue(AiModels.supports(context,option.getString("id"),efforts.getJSONObject(j).getString("value")));
            if(option.getString("id").equals("saved-custom-model")){custom=true;assertEquals(1,efforts.length());}
            if(option.getString("id").equals("gpt-4.1-mini"))assertEquals(1,efforts.length());
        }
        assertTrue(custom);assertTrue(AiModels.supports(context,"gpt-5.4-2026-03-05","high"));assertFalse(AiModels.supports(context,"gpt-5.4-future","high"));
    }
    @Test public void defaultAndExplicitNoneHaveDifferentApiSemantics() throws Exception {
        AiSettings settings=new AiSettings(RuntimeEnvironment.getApplication());settings.save("gpt-5.4","default");assertFalse(settings.snapshot().applyTo(new JSONObject()).has("reasoning"));
        settings.save("gpt-5.4","none");assertEquals("none",settings.snapshot().applyTo(new JSONObject()).getJSONObject("reasoning").getString("effort"));
    }
}
