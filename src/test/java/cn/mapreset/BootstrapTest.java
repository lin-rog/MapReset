package cn.mapreset;

import org.junit.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class BootstrapTest {
    Path root,source,target;
    @Before public void setup()throws Exception{root=Files.createTempDirectory("mapreset-bootstrap");source=root.resolve("world/playerdata");target=root.resolve("mapreset_lobby/playerdata");Files.createDirectories(source);Files.createDirectories(target);}
    @After public void cleanup()throws Exception{FilesEx.delete(root.getParent(),root);}
    @Test public void emptyLobbyPlayerDirectoryIsNotAConflict()throws Exception{Files.write(source.resolve("player.dat"),new byte[]{1,2});Bootstrap.mergePlayerFiles(root,source,target);assertArrayEquals(new byte[]{1,2},Files.readAllBytes(target.resolve("player.dat")));assertTrue(Files.exists(source.resolve("player.dat")));}
    @Test public void repeatedMigrationIsIdempotent()throws Exception{Files.write(source.resolve("player.dat"),new byte[]{1,2});Bootstrap.mergePlayerFiles(root,source,target);Bootstrap.mergePlayerFiles(root,source,target);assertArrayEquals(new byte[]{1,2},Files.readAllBytes(target.resolve("player.dat")));}
    @Test public void conflictingLobbyDataIsNeverOverwritten()throws Exception{Files.write(source.resolve("player.dat"),new byte[]{1,2});Files.write(target.resolve("player.dat"),new byte[]{3,4});try{Bootstrap.mergePlayerFiles(root,source,target);fail();}catch(java.io.IOException expected){}assertArrayEquals(new byte[]{3,4},Files.readAllBytes(target.resolve("player.dat")));}
}
