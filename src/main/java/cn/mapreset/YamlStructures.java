package cn.mapreset;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.util.*;

final class YamlStructures implements StructureProvider {
    static final class Cell {final int y;final BlockData block;Cell(int y,BlockData b){this.y=y;block=b;}}
    static final class Entry {StructureSpec spec;Map<Integer,List<Cell>> columns=new HashMap<>();}
    final Map<String,Entry> entries=new LinkedHashMap<>();
    YamlStructures(MapResetPlugin p)throws IOException{
        File folder=new File(p.getDataFolder(),"structures");folder.mkdirs();
        for(char ch='a';ch<='j';ch++){
            String id=String.valueOf(ch);File f=new File(folder,id+".yml");
            if(!f.exists())createDefault(f,p,id);
            YamlConfiguration c=YamlConfiguration.loadConfiguration(f);
            if(!c.isSet("first-floor-y"))throw new IOException(id+" 缺少 first-floor-y；旧版建筑请明确迁移为含5格地基的结构");
            Map<String,StructureSpec.Entry> entrances=new LinkedHashMap<>();
            if(c.isConfigurationSection("entrances"))for(String side:c.getConfigurationSection("entrances").getKeys(false)){
                String k="entrances."+side;entrances.put(side,new StructureSpec.Entry(side,c.getInt(k+".x"),c.getInt(k+".y"),c.getInt(k+".z")));
            }
            Entry e=new Entry();e.spec=new StructureSpec(c.getInt("width"),c.getInt("depth"),c.getInt("height"),c.getInt("first-floor-y"),c.getInt("foundation-layers",5),entrances);
            Set<String> occupied=new HashSet<>();
            for(String line:c.getStringList("blocks")){
                String[] parts=line.split(",",4);if(parts.length!=4)throw new IOException("无效结构行: "+line);
                int x=Integer.parseInt(parts[0].trim()),y=Integer.parseInt(parts[1].trim()),z=Integer.parseInt(parts[2].trim());
                if(x<0||x>=e.spec.width||z<0||z>=e.spec.depth||y<0||y>=e.spec.height)throw new IOException("结构坐标超界: "+line);
                if(!occupied.add(x+","+y+","+z))throw new IOException("重复结构坐标: "+line);
                String raw=parts[3].trim();BlockData data=raw.contains(":")?Bukkit.createBlockData(raw):Bukkit.createBlockData(Objects.requireNonNull(Material.matchMaterial(raw),raw));
                e.columns.computeIfAbsent(x*e.spec.depth+z,k->new ArrayList<>()).add(new Cell(y,data));
            }
            if(e.columns.isEmpty())throw new IOException(id+" 结构为空");
            // Solid floor at all declared doorways, with enough air above.
            for(StructureSpec.Entry door:e.spec.entrances.values()){
                List<Cell> col=e.columns.get(door.x*e.spec.depth+door.z);boolean floor=false;
                if(col!=null)for(Cell cell:col){if(cell.y==door.y&&cell.block.getMaterial().isSolid())floor=true;if(cell.y>door.y&&cell.y<=door.y+3&&!cell.block.getMaterial().isAir())throw new IOException(id+" 入口上方必须预留3格空间");}
                if(!floor)throw new IOException(id+" 入口第一层缺少实心地板");
            }
            entries.put(id,e);
        }
    }
    void createDefault(File file,MapResetPlugin p,String id)throws IOException{
        int w=p.getConfig().getInt("structures."+id+".width",13),d=p.getConfig().getInt("structures."+id+".depth",13),h=11,floor=4;
        List<String> blocks=new ArrayList<>();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)for(int y=0;y<h;y++){
            String material="AIR";
            if(y<=floor)material="STONE_BRICKS";
            else if(y==h-1)material="OAK_PLANKS";
            else if(x==0||z==0||x==w-1||z==d-1){
                boolean door=((x==0||x==w-1)&&Math.abs(z-d/2)<=2)||((z==0||z==d-1)&&Math.abs(x-w/2)<=2);
                material=door&&y<=floor+3?"AIR":"OAK_PLANKS";
            }
            blocks.add(x+","+y+","+z+","+material);
        }
        YamlConfiguration c=new YamlConfiguration();c.set("width",w);c.set("depth",d);c.set("height",h);c.set("foundation-layers",5);c.set("first-floor-y",floor);c.set("blocks",blocks);c.save(file);
    }
    public StructureSpec spec(String id){return entries.get(id).spec;}
    public void placeColumn(World world,String id,int ox,int oy,int oz,int localX,int localZ){
        Entry e=entries.get(id);List<Cell> col=e.columns.get(localX*e.spec.depth+localZ);
        if(col!=null)for(Cell cell:col)world.getBlockAt(ox+localX,oy+cell.y,oz+localZ).setBlockData(cell.block,false);
    }
}
