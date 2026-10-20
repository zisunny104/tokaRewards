package dev.toka.rewards.plan;
import java.util.Map;
/** Published profiles are bounded in memory; field controls are independent of Plan installation. */
public record PlanSettings(boolean enabled, boolean streak, boolean total, boolean lastDay,
                           boolean automatic, boolean pending, int maxSnapshots) {
 public static PlanSettings parse(Map<String,Object> root){
  Map<?,?> p=root.get("plan") instanceof Map<?,?> m?m:Map.of();
  int max=p.get("max-snapshots") instanceof Number n?n.intValue():1000;
  PlanControl.Options o=PlanControl.parse(root,java.util.Set.of("streak","total","last-day","automatic","pending"));
  return new PlanSettings(o.enabled(),o.has("streak"),o.has("total"),o.has("last-day"),
   o.has("automatic"),o.has("pending"),Math.max(1,Math.min(10000,max)));
 }
 private static boolean on(Map<?,?> p,String key){return Boolean.TRUE.equals(p.get(key));}
}
