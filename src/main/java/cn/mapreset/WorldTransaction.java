package cn.mapreset;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Disk-only journal. Call swap ONLY while all managed worlds are unloaded. */
final class WorldTransaction {
    static final String ID_FILE="mapreset-identity.yml";
    final Path root,dir;final String[] names;
    WorldTransaction(Path root,Path data,String[] names){this.root=root;this.dir=data.resolve("transaction");this.names=names.clone();}
    boolean pending(){return Files.isRegularFile(dir.resolve("journal.yml"));}
    YamlConfiguration journal(){return YamlConfiguration.loadConfiguration(dir.resolve("journal.yml").toFile());}
    synchronized void write(YamlConfiguration c)throws IOException{
        Path temporary=dir.resolve("journal.tmp");c.save(temporary.toFile());
        try{Files.move(temporary,dir.resolve("journal.yml"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(temporary,dir.resolve("journal.yml"),StandardCopyOption.REPLACE_EXISTING);}
    }
    static String fingerprint(Path world)throws IOException{
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");List<Path> files=new ArrayList<>();
            Files.walkFileTree(world,new SimpleFileVisitor<Path>(){
                @Override public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{FilesEx.safe(world.getParent(),directory);return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{FilesEx.safe(world.getParent(),file);if(attrs.isRegularFile())files.add(file);return FileVisitResult.CONTINUE;}
            });files.sort(Comparator.comparing(a->world.relativize(a).toString()));
            byte[] buffer=new byte[65536];
            for(Path path:files){String relative=world.relativize(path).toString().replace('\\','/');String name=path.getFileName().toString();
                if(name.equals("uid.dat")||name.equals("session.lock")||name.equals(ID_FILE))continue;
                FilesEx.safe(world.getParent(),path);digest.update(relative.getBytes("UTF-8"));digest.update((byte)0);
                try(InputStream in=Files.newInputStream(path)){int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}digest.update((byte)0xff);
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    String prepare(Path reserve,Path nether,Path end)throws IOException{
        if(Files.exists(dir))throw new IOException("transaction 目录已有任务，请先 recover");Files.createDirectories(dir);
        try{
            Path[] sources={reserve,nether,end};String id=UUID.randomUUID().toString();YamlConfiguration c=new YamlConfiguration();
            c.set("format",2);c.set("id",id);c.set("state","PREPARED");c.set("reserve",root.relativize(reserve).toString());
            for(int i=0;i<3;i++){
                Path source=sources[i],stage=dir.resolve("new-"+i);
                if(!Files.isRegularFile(source.resolve("level.dat")))throw new IOException("模板缺少level.dat: "+source);
                c.set("names."+i,names[i]);c.set("existed."+i,Files.exists(root.resolve(names[i])));
                String hash=fingerprint(source);FilesEx.copy(root,source,stage);
                if(!hash.equals(fingerprint(stage)))throw new IOException("复制校验失败: "+source);
                c.set("fingerprints."+i,hash);
                YamlConfiguration identity=new YamlConfiguration();identity.set("reset-id",id);identity.set("source-sha256",hash);identity.set("dimension",i);identity.save(stage.resolve(ID_FILE).toFile());
                YamlConfiguration sourceInfo=YamlConfiguration.loadConfiguration(source.resolve("mapreset-template.yml").toFile());
                if(sourceInfo.contains("seed"))c.set("seeds."+i,sourceInfo.getLong("seed"));
            }
            write(c);return id;
        }catch(Exception e){FilesEx.delete(root,dir);throw new IOException("准备失败，活动地图未改动",e);}
    }
    private void validate(YamlConfiguration c)throws IOException{
        if(c.getInt("format")!=2)throw new IOException("旧版本事务未处理，请先用旧版完成或还原后升级");
        for(int i=0;i<3;i++)if(!names[i].equals(c.getString("names."+i)))throw new IOException("事务期间不能更改active世界名");
    }
    void recoverAtStartup()throws IOException{
        if(!pending()){if(Files.exists(dir))FilesEx.delete(root,dir);return;}
        YamlConfiguration c=journal();validate(c);String state=c.getString("state");
        if("ROLLING_BACK".equals(state)){rollback(c);return;}
        if("COMMITTED".equals(state)){cleanup();return;}
        if("SWAPPED".equals(state)){
            for(int i=0;i<3;i++)assertIdentity(root.resolve(names[i]),c,i);return;
        }
        swapUnloaded();
    }
    void swapUnloaded()throws IOException{
        YamlConfiguration c=journal();validate(c);String state=c.getString("state");
        if(!"PREPARED".equals(state)&&!"SWAPPING".equals(state))throw new IOException("不能切换此事务状态: "+state);
        c.set("state","SWAPPING");write(c);
        try{
            for(int i=0;i<3;i++){
                Path target=root.resolve(names[i]),fresh=dir.resolve("new-"+i),old=dir.resolve("old-"+i);
                if(Files.exists(fresh)){
                    assertIdentity(fresh,c,i);
                    if(!c.getString("fingerprints."+i).equals(fingerprint(fresh)))throw new IOException("待启用地图被修改: "+fresh);
                    if(c.getBoolean("existed."+i)&&!Files.exists(target)&&!Files.exists(old))throw new IOException("旧地图及备份丢失");
                    if(Files.exists(target)){
                        if(Files.exists(old))throw new IOException("活动目录被外部程序重新创建: "+target);
                        FilesEx.move(root,target,old);
                    }
                    FilesEx.move(root,fresh,target);
                }else{
                    if(c.getBoolean("existed."+i)&&!Files.exists(old))throw new IOException("旧地图备份丢失");assertIdentity(target,c,i);
                }
            }
            c.set("state","SWAPPED");write(c);
        }catch(Exception e){
            try{c.set("state","ROLLING_BACK");write(c);rollback(c);}catch(Exception rollback){e.addSuppressed(rollback);}
            throw new IOException("切换失败，已尝试回滚；保持大厅隔离直到recover成功",e);
        }
    }
    void assertIdentity(Path folder,YamlConfiguration c,int index)throws IOException{
        if(!Files.isRegularFile(folder.resolve("level.dat")))throw new IOException("世界缺少level.dat: "+folder);
        YamlConfiguration identity=YamlConfiguration.loadConfiguration(folder.resolve(ID_FILE).toFile());
        if(!Objects.equals(c.getString("id"),identity.getString("reset-id"))||identity.getInt("dimension",-1)!=index)throw new IOException("地图更新标识不匹配: "+folder);
    }
    private void rollback(YamlConfiguration c)throws IOException{
        for(int i=2;i>=0;i--){
            Path target=root.resolve(names[i]),old=dir.resolve("old-"+i),fresh=dir.resolve("new-"+i);
            if(Files.exists(old)){
                if(Files.exists(target))FilesEx.delete(root,target);FilesEx.move(root,old,target);
            }else if(!c.getBoolean("existed."+i)&&!Files.exists(fresh)&&Files.exists(target))FilesEx.delete(root,target);
        }
        FilesEx.delete(root,dir);
    }
    void cancelPrepared()throws IOException{
        if(!pending()){if(Files.exists(dir))FilesEx.delete(root,dir);return;}
        if(!"PREPARED".equals(journal().getString("state")))throw new IOException("已开始切换，不能取消");cleanup();
    }
    void rollbackUnloaded()throws IOException{
        if(!pending())throw new IOException("没有可回滚事务");YamlConfiguration c=journal();validate(c);
        if("COMMITTED".equals(c.getString("state")))throw new IOException("已提交/清理的地图不能回滚");
        c.set("state","ROLLING_BACK");write(c);rollback(c);
    }
    void commit()throws IOException{
        YamlConfiguration c=journal();validate(c);if("COMMITTED".equals(c.getString("state")))return;
        if(!"SWAPPED".equals(c.getString("state")))throw new IOException("地图尚未全部切换");
        Path reserve=root.resolve(Objects.requireNonNull(c.getString("reserve"))).normalize();Path pool=dir.getParent().resolve("reserves/overworld");
        if(!Objects.equals(reserve.getParent(),pool))throw new IOException("备用源路径不合法");
        if(Files.exists(reserve))FilesEx.move(root,reserve,dir.resolve("consumed"));else if(!Files.exists(dir.resolve("consumed")))throw new IOException("备用源丢失");
        c.set("state","COMMITTED");write(c);
    }
    void cleanup()throws IOException{FilesEx.delete(root,dir);}
}
