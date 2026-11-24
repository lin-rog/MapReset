package cn.mapreset;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Bootstrap {
    /** Only changes configuration; the human performs ONE initial restart. */
    static void setup(MapResetPlugin p)throws Exception{
        if(!Bukkit.getOnlinePlayers().isEmpty())throw new IOException("初始化前请让所有玩家下线，避免迁移玩家数据时写入");
        if(p.transaction.pending())throw new IOException("有未处理事务，请先恢复");
        Path properties=Paths.get("server.properties").toAbsolutePath(),bukkit=Paths.get("bukkit.yml").toAbsolutePath();
        Properties props=new Properties();try(InputStream in=Files.newInputStream(properties)){props.load(in);}
        String oldDefault=props.getProperty("level-name","world");
        if(oldDefault.equals(p.lobbyName)){throw new IOException("默认世界已经是大厅，无需重复setup");}
        for(String name:p.names)if(name.equals(p.lobbyName))throw new IOException("大厅不能与游戏三界同名");
        String suffix=".mapreset-backup-"+System.currentTimeMillis();Files.copy(properties,properties.resolveSibling(properties.getFileName()+suffix));
        if(Files.exists(bukkit))Files.copy(bukkit,bukkit.resolveSibling(bukkit.getFileName()+suffix));
        p.getConfig().set("active.overworld",p.names[0]);p.getConfig().set("active.nether",p.names[1]);p.getConfig().set("active.end",p.names[2]);p.saveConfig();
        Path lobbyFolder=p.root.resolve(p.lobbyName);
        // Bukkit stores player data in the default world's folder. Migrate player files only.
        for(String child:new String[]{"playerdata","stats","advancements"}){
            Path source=p.root.resolve(oldDefault).resolve(child),target=lobbyFolder.resolve(child);
            if(Files.isDirectory(source)){
                mergePlayerFiles(p.root,source,target);
            }
        }
        YamlConfiguration configuration=YamlConfiguration.loadConfiguration(bukkit.toFile());
        for(String world:new String[]{p.lobbyName,p.lobbyName+"_nether",p.lobbyName+"_the_end"})configuration.set("worlds."+world+".generator","MapReset:lobby");configuration.save(bukkit.toFile());
        // Preserve all unrelated bytes/comments/encoding of server.properties.
        String contents=new String(Files.readAllBytes(properties),java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Pattern key=java.util.regex.Pattern.compile("(?m)^[ \\t]*level-name[ \\t]*[:=][^\\r\\n]*");
        java.util.regex.Matcher matcher=key.matcher(contents);
        contents=matcher.find()?matcher.replaceAll(java.util.regex.Matcher.quoteReplacement("level-name="+p.lobbyName)):contents+"\nlevel-name="+p.lobbyName+"\n";
        Files.write(properties,contents.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }
    static void mergePlayerFiles(Path root,Path source,Path target)throws IOException{
        List<Path> files=new ArrayList<>();FilesEx.safe(root,source);FilesEx.safe(root,target);
        Files.walkFileTree(source,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path folder,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{FilesEx.safe(root,folder);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{
                FilesEx.safe(root,file);Path to=target.resolve(source.relativize(file));FilesEx.safe(root,to);
                if(Files.exists(to)&&!Arrays.equals(Files.readAllBytes(file),Files.readAllBytes(to)))throw new IOException("大厅玩家数据有冲突，请人工合并: "+to);
                files.add(file);return FileVisitResult.CONTINUE;
            }
        });
        Files.createDirectories(target);
        for(Path file:files){Path to=target.resolve(source.relativize(file));Files.createDirectories(to.getParent());if(!Files.exists(to))Files.copy(file,to);}
    }
}
