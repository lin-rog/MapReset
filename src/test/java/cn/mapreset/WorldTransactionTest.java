package cn.mapreset;
import org.junit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
public class WorldTransactionTest {
    Path root,data,reserve,nether,end;String[] names={"world","world_nether","world_the_end"};WorldTransaction tx;
    @Before public void setup() throws Exception {
        root=Files.createTempDirectory("mapreset-test");data=root.resolve("plugins/MapReset");reserve=data.resolve("reserves/overworld/map-1");nether=data.resolve("templates/nether");end=data.resolve("templates/end");
        world(reserve,"new-main");world(nether,"new-nether");world(end,"new-end");
        for(String n:names)world(root.resolve(n),"old-"+n);tx=new WorldTransaction(root,data,names);
    }
    void world(Path p,String content)throws Exception{Files.createDirectories(p);Files.write(p.resolve("level.dat"),content.getBytes("UTF-8"));Files.write(p.resolve("uid.dat"),new byte[]{1});}
    String read(Path p)throws Exception{return new String(Files.readAllBytes(p.resolve("level.dat")),"UTF-8");}
    @After public void cleanup()throws Exception{FilesEx.delete(root.getParent(),root);}
    @Test public void consumesOnlyOverworldAfterCommit()throws Exception{
        tx.prepare(reserve,nether,end);assertEquals("old-world",read(root.resolve("world")));
        tx.recoverAtStartup();tx.recoverAtStartup();assertEquals("new-main",read(root.resolve("world")));assertTrue(Files.exists(reserve));
        assertFalse(Files.exists(root.resolve("world/uid.dat")));assertEquals("old-world",read(tx.dir.resolve("old-0")));
        tx.commit();assertFalse(Files.exists(reserve));assertEquals("new-nether",read(nether));assertEquals("new-end",read(end));
        tx.cleanup();assertFalse(tx.pending());
    }
    @Test public void recoversInterruptedSwap()throws Exception{
        tx.prepare(reserve,nether,end);
        YamlConfiguration c=YamlConfiguration.loadConfiguration(tx.dir.resolve("journal.yml").toFile());c.set("state","SWAPPING");tx.write(c);
        FilesEx.move(root,root.resolve("world"),tx.dir.resolve("old-0"));
        FilesEx.move(root,tx.dir.resolve("new-0"),root.resolve("world"));
        FilesEx.move(root,root.resolve("world_nether"),tx.dir.resolve("old-1"));
        tx.recoverAtStartup();assertEquals("new-main",read(root.resolve("world")));assertEquals("new-nether",read(root.resolve("world_nether")));assertEquals("new-end",read(root.resolve("world_the_end")));
    }
    @Test public void refusesOutOfRootDelete()throws Exception{
        try{FilesEx.delete(root,root);fail();}catch(java.io.IOException expected){}
        assertTrue(Files.exists(root));
    }
    @Test public void missingTemplateLeavesLiveWorldsUnchanged()throws Exception{
        Files.delete(nether.resolve("level.dat"));try{tx.prepare(reserve,nether,end);fail();}catch(java.io.IOException expected){}
        assertEquals("old-world",read(root.resolve("world")));assertFalse(tx.pending());assertTrue(Files.exists(reserve));
    }
    @Test public void damagedStageRollsBackWholeSwap()throws Exception{
        tx.prepare(reserve,nether,end);Files.delete(tx.dir.resolve("new-1/level.dat"));
        try{tx.recoverAtStartup();fail();}catch(java.io.IOException expected){}
        for(String name:names)assertEquals("old-"+name,read(root.resolve(name)));
        assertTrue(Files.exists(reserve));assertFalse(tx.pending());
    }
    @Test public void modifiedCopiedRegionIsRejectedBeforeActivation()throws Exception{
        Files.createDirectories(reserve.resolve("region"));Files.write(reserve.resolve("region/r.0.0.mca"),new byte[]{1,2,3});tx.prepare(reserve,nether,end);
        Files.write(tx.dir.resolve("new-0/region/r.0.0.mca"),new byte[]{8,9});
        try{tx.swapUnloaded();fail();}catch(java.io.IOException expected){}
        for(String name:names)assertEquals("old-"+name,read(root.resolve(name)));assertTrue(Files.exists(reserve));
    }
    @Test public void operatorRollbackRestoresThreeWorldsWithoutConsumingReserve()throws Exception{
        tx.prepare(reserve,nether,end);tx.swapUnloaded();tx.rollbackUnloaded();
        for(String name:names)assertEquals("old-"+name,read(root.resolve(name)));assertTrue(Files.exists(reserve));assertFalse(tx.pending());
    }
    @Test public void committedCleanupRecoveryDoesNotSwapAgain()throws Exception{
        tx.prepare(reserve,nether,end);tx.swapUnloaded();tx.commit();tx.recoverAtStartup();
        assertEquals("new-main",read(root.resolve("world")));assertFalse(Files.exists(reserve));assertFalse(tx.pending());assertTrue(Files.exists(nether));assertTrue(Files.exists(end));
    }
}
