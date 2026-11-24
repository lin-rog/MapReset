package cn.mapreset;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator;
import java.util.Random;
/** Permanent lobby only. Gameplay worlds always use the vanilla generator. */
public final class MapGenerator extends ChunkGenerator {
 public MapGenerator(boolean ignored){}
 @Override public ChunkData generateChunkData(World w,Random random,int cx,int cz,BiomeGrid biomes){
  ChunkData data=createChunkData(w);for(int x=0;x<16;x++)for(int z=0;z<16;z++)biomes.setBiome(x,z,Biome.THE_VOID);return data;
 }
 @Override public boolean shouldGenerateCaves(){return false;}
 @Override public boolean shouldGenerateDecorations(){return false;}
 @Override public boolean shouldGenerateMobs(){return false;}
 @Override public boolean shouldGenerateStructures(){return false;}
 @Override public boolean canSpawn(World w,int x,int z){return true;}
 @Override public Location getFixedSpawnLocation(World w,Random random){return new Location(w,.5,100,.5);}
}
