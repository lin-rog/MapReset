package cn.mapreset;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

final class FilesEx {
    static void safe(Path root, Path p) throws IOException {
        root = root.toAbsolutePath().normalize(); p = p.toAbsolutePath().normalize();
        if (p.equals(root) || !p.startsWith(root)) throw new IOException("路径越界: " + p);
        for (Path q = p; q != null && q.startsWith(root); q = q.getParent())
            if (Files.isSymbolicLink(q) || (Files.exists(q, LinkOption.NOFOLLOW_LINKS) && Files.readAttributes(q, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("拒绝链接/重解析路径: " + q);
        if(Files.exists(root)){
            Path realRoot=root.toRealPath();
            for(Path q=p;q!=null&&q.startsWith(root);q=q.getParent())if(Files.exists(q)){
                if(!q.toRealPath().equals(realRoot.resolve(root.relativize(q))))throw new IOException("拒绝链接路径: "+q);
                break;
            }
        }
    }
    static void delete(Path root, Path p) throws IOException {
        safe(root,p); if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException { safe(root,d); return FileVisitResult.CONTINUE; }
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { safe(root,f); Files.delete(f); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { if(e!=null) throw e; Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }
    static void copy(Path root, Path from, Path to) throws IOException {
        safe(root,from); safe(root,to);
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                safe(root,d); Files.createDirectories(to.resolve(from.relativize(d))); return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                safe(root,f); String n=f.getFileName().toString();
                if (!n.equals("session.lock") && !n.equals("uid.dat")) Files.copy(f,to.resolve(from.relativize(f)),StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static void move(Path root,Path a,Path b) throws IOException {
        safe(root,a); safe(root,b); Files.createDirectories(b.getParent());
        try { Files.move(a,b,StandardCopyOption.ATOMIC_MOVE); } catch(AtomicMoveNotSupportedException e) { Files.move(a,b); }
    }
    static List<Path> ready(Path pool) throws IOException {
        List<Path> list=new ArrayList<>(); if(!Files.isDirectory(pool)) return list;
        try(DirectoryStream<Path> ds=Files.newDirectoryStream(pool)) {
            for(Path p:ds) if(!p.getFileName().toString().endsWith(".building") && Files.isDirectory(p) && Files.isRegularFile(p.resolve("READY")) && Files.isRegularFile(p.resolve("level.dat"))) list.add(p);
        } return list;
    }
}
