package dev.toka.rewards;

import com.google.gson.*;
import dev.toka.rewards.core.*;
import dev.toka.storage.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RewardsServiceTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private final UUID player=UUID.randomUUID();
    private static RewardSettings settings(boolean enabled){return new RewardSettings(ZoneId.of("Asia/Taipei"),"money",50,75,1,BigDecimal.ZERO,2,100,enabled,true,10);}
    private static final class MutableClock extends Clock {
        Instant time=Instant.parse("2026-10-31T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(time,zone);}
        public Instant instant(){return time;}
    }
    private static final class Bank implements RewardsService.Payment {
        final Map<String,Long> receipts=new HashMap<>();
        long balance;
        boolean unavailable;
        public CompletableFuture<Boolean> deposit(UUID player,String currency,long amount,String operation){
            if(unavailable)return CompletableFuture.completedFuture(false);
            if(!receipts.containsKey(operation)){receipts.put(operation,amount);balance+=amount;}
            return CompletableFuture.completedFuture(true);
        }
    }
    private RewardsService service(DocumentStore store,RewardSettings settings,Clock clock,Bank bank)throws Exception{
        return new RewardsService(store,settings,clock,bank,error->{});
    }
    @Test public void duplicateClaimsAndMonthYearBoundariesUseCalendarDates()throws Exception{
        MutableClock clock=new MutableClock();Bank bank=new Bank();
        try(var rewards=service(DocumentStore.open(new StoreConfig(folder.newFolder().toPath(),1)),settings(true),clock,bank)){
            assertEquals("PAID",rewards.daily(player,"Player",-1).get().status());
            assertEquals("ALREADY",rewards.daily(player,"Player",-1).get().status());assertEquals(50,bank.balance);
            clock.time=Instant.parse("2026-11-01T12:00:00Z");
            assertEquals(2,rewards.daily(player,"Player",-1).get().streak());assertEquals(125,bank.balance);
            clock.time=Instant.parse("2026-12-31T12:00:00Z");assertEquals(1,rewards.daily(player,"Player",-1).get().streak());
            clock.time=Instant.parse("2027-01-01T12:00:00Z");assertEquals(2,rewards.daily(player,"Player",-1).get().streak());
        }
    }
    @Test public void unavailableEconomyPreservesPendingAndRestartRetriesSameIdAcrossEveryBackend()throws Exception{
        for(Backend backend:Backend.values()){
            var path=folder.newFolder().toPath();MutableClock clock=new MutableClock();Bank bank=new Bank();bank.unavailable=true;
            try(var rewards=service(DocumentStore.open(new StoreConfig(backend,path,1)),settings(true),clock,bank)){
                assertEquals("PENDING",rewards.daily(player,"Player",-1).get().status());assertEquals(1,(int)rewards.pendingCount().get());
            }
            bank.unavailable=false;
            try(var rewards=service(DocumentStore.open(new StoreConfig(backend,path,1)),settings(true),clock,bank)){
                rewards.retryPending().get();assertEquals(0,(int)rewards.pendingCount().get());assertEquals(50,bank.balance);
                assertEquals("ALREADY",rewards.daily(player,"Player",-1).get().status());
            }
        }
    }
    @Test public void generalGrantIdsRejectChangedAmountsAndRemainIdempotent()throws Exception{
        Bank bank=new Bank();
        try(var rewards=service(DocumentStore.open(new StoreConfig(folder.newFolder().toPath(),1)),settings(false),Clock.systemUTC(),bank)){
            assertEquals("PAID",rewards.grant(player,"autumn","completion-1","money",100).get().status());
            assertEquals("ALREADY",rewards.grant(player,"autumn","completion-1","money",100).get().status());
            try{rewards.grant(player,"autumn","completion-1","money",200).get();fail();}catch(ExecutionException expected){}
            assertEquals(100,bank.balance);
        }
    }
    @Test public void importsAreEmptyOrEqualAndNeverGrantTodaysRewardTwice()throws Exception{
        Bank bank=new Bank();MutableClock clock=new MutableClock();
        try(var rewards=service(DocumentStore.open(new StoreConfig(folder.newFolder().toPath(),1)),settings(false),clock,bank)){
            JsonObject profile=new JsonObject();profile.addProperty("uuid",player.toString());profile.addProperty("name","Player");
            profile.addProperty("lastDay","2026-10-31");profile.addProperty("streak",3);profile.addProperty("total",3);profile.addProperty("automatic",true);
            JsonArray rows=new JsonArray();rows.add(profile);assertEquals(1,(int)rewards.importProfiles(rows.toString()).get());
            assertEquals(1,(int)rewards.importProfiles(rows.toString()).get());
            profile.addProperty("streak",4);try{rewards.importProfiles(rows.toString()).get();fail();}catch(ExecutionException expected){}
            rewards.settings(settings(true));assertEquals("ALREADY",rewards.daily(player,"Player",-1).get().status());assertEquals(0,bank.balance);
            assertTrue(rewards.profile(player).get().get("automatic").getAsBoolean());
        }
    }
    @Test public void crashAfterCreditButBeforePaidMarkerDoesNotDuplicateMoney()throws Exception{
        Bank bank=new Bank();var path=folder.newFolder().toPath();DocumentStore real=DocumentStore.open(new StoreConfig(path,1));
        DocumentStore failing=new DocumentStore(){
            public Optional<JsonObject> load(String key)throws IOException{return real.load(key);}
            public void save(String key,JsonObject value)throws IOException{real.save(key,value);}
            public void saveAll(Map<String,JsonObject> docs)throws IOException{
                if(docs.values().stream().anyMatch(v->v.has("state")&&v.get("state").getAsString().equals("PAID")))throw new IOException("Injected marker failure");
                real.saveAll(docs);
            }
            public void delete(String key)throws IOException{real.delete(key);}
            public Set<String> keys()throws IOException{return real.keys();}
            public SortedMap<String,JsonObject> snapshot()throws IOException{return real.snapshot();}
            public void close()throws IOException{real.close();}
        };
        try(var rewards=service(failing,settings(true),Clock.systemUTC(),bank)){
            try{rewards.daily(player,"Player",-1).get();fail();}catch(ExecutionException expected){}assertEquals(50,bank.balance);
        }
        try(var rewards=service(DocumentStore.open(new StoreConfig(path,1)),settings(true),Clock.systemUTC(),bank)){
            rewards.retryPending().get();assertEquals(0,(int)rewards.pendingCount().get());assertEquals(50,bank.balance);
        }
    }
}
