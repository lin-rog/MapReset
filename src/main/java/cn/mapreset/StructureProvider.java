package cn.mapreset;

import org.bukkit.World;

/** All calls run on the server thread. spec() is snapshotted when generation starts.
 * placeColumn must only edit the requested LOCAL X/Z column inside the spec bounds.
 * This supports variable structures while allowing the plugin to budget work per tick.
 * Five foundation layers include firstFloor; entrances must have three blocks of headroom.
 */
public interface StructureProvider {
    default String revision(){return getClass().getName();}
    StructureSpec spec(String id);
    void placeColumn(World world,String id,int originX,int originY,int originZ,int localX,int localZ);
}
