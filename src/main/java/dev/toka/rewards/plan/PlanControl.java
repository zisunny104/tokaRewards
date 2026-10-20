package dev.toka.rewards.plan;

import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Optional Plan configuration and comment-preserving administrative controls. */
public final class PlanControl {
 public record Options(String mode, Set<String> fields) {
  public Options { fields=Set.copyOf(fields); }
  public boolean enabled(){return !mode.equals("disable");}
  public boolean has(String field){return fields.contains("all") || fields.contains(field);}
 }
 private final PluginBase plugin;private final Runnable refresh;private final Supplier<Set<String>> known;
 private final BiFunction<String,Object[],String> text;private final String command;
 private boolean announced,registered;
 public PlanControl(PluginBase plugin,Runnable refresh,Supplier<Set<String>> known,
                    BiFunction<String,Object[],String> text,String command){
  this.plugin=plugin;this.refresh=refresh;this.known=known;this.text=text;this.command=command;
 }
 public static Options parse(Map<String,Object> root, Set<String> known){
  Map<?,?> p=root.get("plan") instanceof Map<?,?> m?m:Map.of();
  String mode=p.containsKey("mode")?p.get("mode").toString():p.containsKey("enabled")?(Boolean.TRUE.equals(p.get("enabled"))?"enable":"disable"):"auto";
  if(!Set.of("auto","enable","disable").contains(mode))throw new IllegalArgumentException("plan.mode");
  Set<String> fields=new LinkedHashSet<>();
  if(p.containsKey("fields")){
   Object value=p.get("fields");if(value instanceof List<?> list)list.forEach(v->fields.add(v.toString()));else fields.add(value.toString());
  } else {
   boolean legacy=false;
   for(String field:known){Object value=p.get(field);if(value instanceof Boolean){legacy=true;if(Boolean.TRUE.equals(value))fields.add(field);}}
   if(!legacy)fields.add("all");
  }
  validateFields(fields,known);
  fields.remove("none");return new Options(mode,fields);
 }
 private static void validateFields(Set<String> fields,Set<String> known){
  if((fields.contains("all")||fields.contains("none"))&&fields.size()!=1)throw new IllegalArgumentException("plan.fields");
  for(String field:fields)if(!field.equals("all")&&!field.equals("none")&&!known.contains(field))throw new IllegalArgumentException("plan.fields: "+field);
 }
 public Options options(){return parse(plugin.getConfig().getAll(),known.get());}
 public boolean shouldRegister(){
  registered=false;Options options=options();if(!options.enabled())return false;
  var plan=plugin.getServer().getPluginManager().getPlugin("Plan");
  if(plan==null||!plan.isEnabled()){
   if(options.mode().equals("enable"))plugin.getLogger().warning(text.apply("plan.missing",new Object[]{command}));
   return false;
  }
  if(!announced){announced=true;plugin.getLogger().info(text.apply("plan.detected",new Object[]{options.mode(),command}));}
  return true;
 }
 public void registered(boolean value){registered=value;}
 public String statusJson(){Options options=options();JsonObject out=new JsonObject();out.addProperty("mode",options.mode());
  out.addProperty("registered",registered);out.add("fields",new com.google.gson.Gson().toJsonTree(options.fields()));
  out.add("availableFields",new com.google.gson.Gson().toJsonTree(known.get()));return out.toString();}
 public boolean configure(String mode,String[] fields){
  try{
   Options current=options();String nextMode=mode==null?current.mode():mode;
   if(!Set.of("auto","enable","disable").contains(nextMode))return false;
   Set<String> selected=fields==null?current.fields():new LinkedHashSet<>(Arrays.asList(fields));validateFields(selected,known.get());
   if(selected.isEmpty())selected=Set.of("none");
   Path file=plugin.getDataFolder().toPath().resolve("config.yml");String before=Files.readString(file,StandardCharsets.UTF_8);
   String after=edit(before,nextMode,selected);write(file,after);
   plugin.reloadConfig();
   refresh.run();return true;
  }catch(Exception|LinkageError e){plugin.getLogger().warning(text.apply("plan.configureFailed",new Object[]{e.getClass().getSimpleName()}));return false;}
 }
 private static void write(Path file,String content)throws java.io.IOException{
  Path temporary=file.resolveSibling("."+file.getFileName()+"."+UUID.randomUUID()+".tmp");
  try{Files.writeString(temporary,content,StandardCharsets.UTF_8);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
  finally{Files.deleteIfExists(temporary);}
 }
 public boolean command(CommandSender sender,String[] args,String permission){
  if(args.length==0||!args[0].equalsIgnoreCase("plan"))return false;
  if(!sender.hasPermission(permission)){sender.sendMessage(text.apply("plan.noPermission",new Object[0]));return true;}
  if(args.length==2&&args[1].equalsIgnoreCase("status")){sender.sendMessage(statusJson());return true;}
  boolean ok=false;
  if(args.length==3&&args[1].equalsIgnoreCase("mode"))ok=configure(args[2],null);
  else if(args.length>=3&&args[1].equalsIgnoreCase("fields"))ok=configure(null,Arrays.copyOfRange(args,2,args.length));
  sender.sendMessage(text.apply(ok?"plan.configured":"plan.usage",new Object[]{command}));return true;
 }
 public static String edit(String source,String mode,Set<String> fields){
  List<String> lines=new ArrayList<>(Arrays.asList(source.split("\\R",-1)));int start=-1,end=lines.size();
  for(int i=0;i<lines.size();i++)if(lines.get(i).matches("plan\\s*:.*")){start=i;break;}
  if(start<0){lines.add("plan:");start=lines.size()-1;end=lines.size();}
  else for(int i=start+1;i<lines.size();i++){String line=lines.get(i);if(!line.isBlank()&&!line.startsWith(" ")&&!line.startsWith("#")){end=i;break;}}
  for(String key:List.of("mode","fields"))for(int i=start+1;i<end;i++)if(lines.get(i).matches("  "+key+"\\s*:.*")){
   lines.remove(i);end--;while(i<end&&lines.get(i).matches("\\s+-.*")){lines.remove(i);end--;}break;
  }
  String values=fields.stream().map(s->"'"+s+"'").collect(java.util.stream.Collectors.joining(", "));
  lines.add(start+1,"  fields: ["+values+"]");lines.add(start+1,"  mode: "+mode);
  return String.join("\n",lines);
 }
}

