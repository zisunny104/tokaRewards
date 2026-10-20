package dev.toka.rewards;
import com.google.gson.JsonParser;
import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.extractor.ExtensionExtractor;
import dev.toka.rewards.plan.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PlanTest {
 @Test public void boundedSnapshotsNeverWaitOrReadMutableJson(){
  PlanExtension extension=new PlanExtension(new PlanSettings(true,true,true,true,true,true,1),s->s);
  new ExtensionExtractor(extension).validateAnnotations();
  UUID one=UUID.randomUUID(),two=UUID.randomUUID();
  var profile=JsonParser.parseString("{\"lastDay\":\"2026-10-20\",\"streak\":2,\"total\":9,\"automatic\":true}").getAsJsonObject();
  extension.publish(one,profile);profile.addProperty("streak",99);assertEquals(2,extension.snapshot(one).streak());
  extension.publish(two,profile);
  try{extension.snapshot(one);fail();}catch(NotReadyException expected){}
  try{extension.pendingSnapshot();fail();}catch(NotReadyException expected){}
  extension.publishPending(3);assertEquals(3,extension.pendingSnapshot());
  extension.close();extension.publish(one,profile);
  try{extension.snapshot(one);fail();}catch(NotReadyException expected){}
 }
 @Test public void autoDefaultsAndExplicitDisable(){
  PlanSettings s=PlanSettings.parse(Map.of());assertTrue(s.enabled());assertTrue(s.streak());
  assertTrue(s.total());assertTrue(s.lastDay());assertTrue(s.automatic());assertTrue(s.pending());
  assertFalse(PlanSettings.parse(Map.of("plan",Map.of("mode","disable","fields","none"))).enabled());
 }
}
