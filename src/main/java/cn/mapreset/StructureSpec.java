package cn.mapreset;

import java.util.*;

/** Immutable geometry; firstFloor is local Y of the WALKABLE FLOOR BLOCK. */
public final class StructureSpec {
    public static final class Entry {
        public final String side;public final int x,y,z;
        public Entry(String side,int x,int y,int z){this.side=side;this.x=x;this.y=y;this.z=z;}
    }
    public final int width,depth,height,firstFloor,foundationLayers;
    public final Map<String,Entry> entrances;
    public StructureSpec(int width,int depth,int height,int firstFloor,int foundationLayers,Map<String,Entry> entries){
        if(width<3||depth<3||width>600||depth>600||height<6||height>240||foundationLayers<5||firstFloor<foundationLayers-1||firstFloor>=height-2)
            throw new IllegalArgumentException("尺寸/第一层无效：宽深3..600，高6..240，地基至少5层，第一层必须在结构内且上方至少2格");
        this.width=width;this.depth=depth;this.height=height;this.firstFloor=firstFloor;this.foundationLayers=foundationLayers;
        Map<String,Entry> m=new LinkedHashMap<>();
        if(entries!=null)m.putAll(entries);
        if(m.isEmpty()){
            m.put("north",new Entry("north",width/2,firstFloor,0));m.put("south",new Entry("south",width/2,firstFloor,depth-1));
            m.put("west",new Entry("west",0,firstFloor,depth/2));m.put("east",new Entry("east",width-1,firstFloor,depth/2));
        }
        for(Entry e:m.values()){
            if(e.x<0||e.x>=width||e.z<0||e.z>=depth||e.y!=firstFloor)throw new IllegalArgumentException("入口必须位于第一层边缘");
            if(!((e.side.equals("north")&&e.z==0)||(e.side.equals("south")&&e.z==depth-1)||(e.side.equals("west")&&e.x==0)||(e.side.equals("east")&&e.x==width-1)))throw new IllegalArgumentException("入口侧面和坐标不符");
        }
        entrances=Collections.unmodifiableMap(m);
    }
}
