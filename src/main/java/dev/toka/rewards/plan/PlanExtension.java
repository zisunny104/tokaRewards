package dev.toka.rewards.plan;
import com.google.gson.JsonObject;
import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.*;
import com.djrapitops.plan.extension.annotation.*;
import com.djrapitops.plan.extension.builder.ExtensionDataBuilder;
import java.util.*;
import java.util.function.Function;

/** Main owns async data loading; Plan providers never wait on storage or request another refresh. */
@PluginInfo(name="tokaRewards",iconName="gift")
public final class PlanExtension implements DataExtension,AutoCloseable {
 public record Summary(String lastDay,long streak,long total,boolean automatic){}
 private final PlanSettings settings;
 private final Function<String,String> text;
 private final LinkedHashMap<UUID,Summary> cache=new LinkedHashMap<>(16,0.75f,true);
 private volatile boolean active=true;
 private volatile Integer pending;
 private ExtensionService registered;
 public PlanExtension(PlanSettings settings,Function<String,String> text){this.settings=settings;this.text=text;}
 public boolean register(){
  if(!CapabilityService.getInstance().hasCapability("DATA_EXTENSION_BUILDER_API"))return false;
  ExtensionService service=ExtensionService.getInstance();if(service.register(this).isEmpty())return false;registered=service;return true;
 }
 public synchronized void publish(UUID id,JsonObject profile){
  if(!active)return;
  Summary value=new Summary(profile.get("lastDay").getAsString(),profile.get("streak").getAsLong(),
   profile.get("total").getAsLong(),profile.get("automatic").getAsBoolean());
  cache.put(id,value);
  while(cache.size()>settings.maxSnapshots())cache.remove(cache.keySet().iterator().next());
 }
 public void publishPending(int count){if(active && count>=0)pending=count;}
 public synchronized Summary snapshot(UUID id){
  Summary value=cache.get(id);if(!active || value==null)throw new NotReadyException();return value;
 }
 public int pendingSnapshot(){Integer value=pending;if(!active || value==null)throw new NotReadyException();return value;}
 @Override public CallEvents[] callExtensionMethodsOn(){return new CallEvents[]{CallEvents.PLAYER_JOIN,
  CallEvents.PLAYER_LEAVE,CallEvents.PLAYER_PERIODICAL,CallEvents.SERVER_PERIODICAL,CallEvents.SERVER_EXTENSION_REGISTER};}
 @DataBuilderProvider public ExtensionDataBuilder player(UUID id){
  Summary s=snapshot(id);ExtensionDataBuilder b=newExtensionDataBuilder();
  if(settings.streak())b.addValue(Long.class,b.valueBuilder(text.apply("plan.streak")).buildNumber(s.streak()));
  if(settings.total())b.addValue(Long.class,b.valueBuilder(text.apply("plan.total")).buildNumber(s.total()));
  if(settings.lastDay())b.addValue(String.class,b.valueBuilder(text.apply("plan.lastDay")).buildString(s.lastDay()));
  if(settings.automatic())b.addValue(Boolean.class,b.valueBuilder(text.apply("plan.automatic")).buildBoolean(s.automatic()));
  return b;
 }
 @DataBuilderProvider public ExtensionDataBuilder server(){
  ExtensionDataBuilder b=newExtensionDataBuilder();
  if(settings.pending())b.addValue(Long.class,b.valueBuilder(text.apply("plan.pending")).buildNumber((long)pendingSnapshot()));
  return b;
 }
 @Override public synchronized void close(){active=false;cache.clear();pending=null;
  if(registered!=null){registered.unregister(this);registered=null;}}
}
