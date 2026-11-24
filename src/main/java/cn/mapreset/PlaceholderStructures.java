package cn.mapreset;
import org.bukkit.*;import java.util.*;
final class PlaceholderStructures implements StructureProvider {
 final Map<String,StructureSpec> specs=new LinkedHashMap<>();
 PlaceholderStructures(StructureProvider fallback,long seed,int min,int max){
  min=Math.max(7,min);max=Math.max(min,Math.min(29,max));Random r=new Random(seed^291781);
  for(char c='a';c<='j';c++){int w=min+r.nextInt(max-min+1),d=min+r.nextInt(max-min+1);specs.put(""+c,new StructureSpec(w,d,11,4,5,null));}
 }
 public StructureSpec spec(String id){return specs.get(id);}
 public void placeColumn(World world,String id,int ox,int oy,int oz,int x,int z){
  StructureSpec s=spec(id);
  for(int y=0;y<=4;y++)world.getBlockAt(ox+x,oy+y,oz+z).setType(Material.STONE_BRICKS,false);
  boolean corner=(x==0||x==s.width-1)&&(z==0||z==s.depth-1);
  if(corner)for(int y=5;y<10;y++)world.getBlockAt(ox+x,oy+y,oz+z).setType(Material.OAK_LOG,false);
  if(corner)world.getBlockAt(ox+x,oy+10,oz+z).setType(Material.GLOWSTONE,false);
 }
}
