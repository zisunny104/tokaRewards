package dev.toka.rewards;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Read-only vote queries. Credentials/URLs/responses never escape through errors or logs. */
public final class VoteClient implements AutoCloseable {
    public record Snapshot(boolean available, long votes, boolean voted, boolean unclaimed) {
        public static Snapshot unavailable() { return new Snapshot(false, -1, false, false); }
    }
    public record Settings(boolean enabled, String endpoint, String key, String voteUrl, int timeout, int ttl, int bytes, int concurrency, int cacheSize, boolean claim) {
        public static Settings parse(Map<?, ?> root) {
            Map<?, ?> vote = (Map<?, ?>) root.get("vote");
            boolean enabled = Boolean.TRUE.equals(vote.get("enabled"));
            String endpoint = text(vote, "endpoint"), key = text(vote, "api-key"), voteUrl = text(vote, "url");
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) throw new IllegalArgumentException("vote.endpoint");
            if (enabled && key.isBlank()) throw new IllegalArgumentException("vote.api-key");
            return new Settings(enabled, endpoint, key, voteUrl, number(vote,"timeout-ms",100,30000), number(vote,"cache-seconds",1,3600),
                    number(vote,"max-response-bytes",1024,4194304), number(vote,"concurrency",1,8), number(vote,"cache-entries",1,10000), !Boolean.FALSE.equals(vote.get("claim")));
        }
        private static String text(Map<?, ?> map, String key) { return Objects.toString(map.get(key), ""); }
        private static int number(Map<?, ?> map, String key, int min, int max) {
            int value = new java.math.BigDecimal(String.valueOf(map.get(key))).intValueExact();
            if (value < min || value > max) throw new IllegalArgumentException("vote."+key); return value;
        }
        @Override public String toString() { return "Vote settings (credentials omitted)"; }
    }
    private record Cached(Snapshot data, long time) {}
    private final Settings settings;
    private final Clock clock;
    private final ExecutorService worker;
    private final Map<String, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentMap<String, CompletableFuture<Snapshot>> pending = new ConcurrentHashMap<>();

    public VoteClient(Settings settings, Clock clock) {
        this.settings = settings; this.clock = clock;
        worker = new ThreadPoolExecutor(settings.concurrency(), settings.concurrency(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.cacheSize()), job -> { Thread t = new Thread(job,"tokaRewards-vote"); t.setDaemon(true); return t; });
    }
    public String url() { return settings.voteUrl(); }
    public boolean enabled() { return settings.enabled(); }
    /** Whether a vote is marked as claimed on the vote site once its reward has been paid */
    public boolean claimEnabled() { return settings.enabled() && settings.claim(); }
    /** Marks the vote of the last 24 hours as claimed. True only when the site answers that it is claimed. */
    public CompletableFuture<Boolean> claim(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String answer = read(settings.endpoint()+"?action=post&object=votes&element=claim&key="+encode(settings.key())+"&username="+encode(name)).trim();
                synchronized (cache) { cache.remove(name); }
                return "1".equals(answer);
            } catch (Exception ignored) { return false; }
        }, worker);
    }
    public CompletableFuture<Snapshot> lookup(String name) {
        if (!settings.enabled()) return CompletableFuture.completedFuture(Snapshot.unavailable());
        Cached old = cache.get(name);
        if (old != null && clock.millis()-old.time() < settings.ttl()*1000L) return CompletableFuture.completedFuture(old.data());
        CompletableFuture<Snapshot> answer = new CompletableFuture<>();
        CompletableFuture<Snapshot> existing = pending.putIfAbsent(name, answer);
        if (existing != null) return existing.copy();
        try { worker.execute(() -> {
            Snapshot result = Snapshot.unavailable();
            try {
                String base = settings.endpoint()+"?key="+encode(settings.key());
                JsonObject root = JsonParser.parseString(read(base+"&object=servers&element=voters&month=current&format=json")).getAsJsonObject();
                long votes = 0;
                for (JsonElement item : root.getAsJsonArray("voters")) {
                    JsonObject row=item.getAsJsonObject();
                    if (name.equalsIgnoreCase(row.get("nickname").getAsString())) votes = row.get("votes").getAsBigDecimal().longValueExact();
                }
                String state=read(base+"&object=votes&element=claim&username="+encode(name)).trim();
                if (votes >= 0 && Set.of("0","1","2").contains(state)) result = new Snapshot(true,votes,!"0".equals(state),"1".equals(state));
            } catch (Exception ignored) { /* Unknown is never represented as a confirmed zero. */ }
            synchronized (cache) {
                cache.put(name,new Cached(result,clock.millis()));
                while(cache.size()>settings.cacheSize())cache.remove(cache.keySet().iterator().next());
            }
            pending.remove(name); answer.complete(result);
        }); } catch (RejectedExecutionException error) { pending.remove(name); answer.complete(Snapshot.unavailable()); }
        return answer.copy();
    }
    private String read(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(settings.timeout()); connection.setReadTimeout(settings.timeout()); connection.setInstanceFollowRedirects(false);
        long deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(settings.timeout());
        try {
            if (connection.getResponseCode()!=200) throw new IOException("Vote query failed");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>settings.bytes()||System.nanoTime()>deadline)throw new IOException("Vote query limit exceeded");
                    output.write(buffer,0,count);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    @Override public void close() { worker.shutdownNow(); pending.values().forEach(f->f.complete(Snapshot.unavailable())); pending.clear(); cache.clear(); }
}
