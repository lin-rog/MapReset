package cn.mapreset;
import org.bukkit.*;
import java.util.*;
final class VanillaScan {
 final int cx,cz;final List<int[]> ores=new ArrayList<>(),surface=new ArrayList<>();
 VanillaScan(int x,int z){cx=x;cz=z;}
 static boolean ground(Material m){String n=m.name();return m.isSolid()&&m.isOccluding()&&!n.endsWith("_LOG")&&!n.endsWith("_WOOD")&&!n.endsWith("_LEAVES")&&!n.contains("MUSHROOM")&&m!=Material.SNOW&&m!=Material.PUMPKIN&&m!=Material.MELON;}
 static VanillaScan scan(ChunkSnapshot chunk,int cx,int cz){
  VanillaScan out=new VanillaScan(cx,cz);
  for(int x=0;x<16;x++)for(int z=0;z<16;z++){
   int wx=cx*16+x,wz=cz*16+z,top=Math.min(255,chunk.getHighestBlockYAt(x,z)),waterLevel=-1;
   while(top>1&&!ground(chunk.getBlockType(x,top,z))){
    Material type=chunk.getBlockType(x,top,z);
    if(type==Material.WATER||type==Material.KELP||type==Material.KELP_PLANT||type==Material.SEAGRASS||type==Material.TALL_SEAGRASS)waterLevel=Math.max(waterLevel,top);
    top--;
   }
   if(wx>=-350&&wx<350&&wz>=-350&&wz<350)out.surface.add(new int[]{wx,wz,top,waterLevel>=0?1:0,waterLevel});
   for(int y=0;y<256;y++){Material m=chunk.getBlockType(x,y,z);if(m==Material.IRON_ORE||m==Material.GOLD_ORE||m==Material.DIAMOND_ORE||m==Material.EMERALD_ORE)out.ores.add(new int[]{wx,y,wz});}
  }return out;
 }
}
