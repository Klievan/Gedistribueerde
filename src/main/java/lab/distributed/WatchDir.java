package lab.distributed;

/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 * Klasse die een bepaalde directory "watched". Als er bestanden bijkomen/verwijderd worden, dan wordt dit opgemerkt (Event)
 * Methode directoryChange in de geassocieerde node wordt aangeroepen om de juiste handeling te ondernemen,
 * elke node heeft een eigen watchDirectory.
 */

import java.nio.file.*;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;

import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;

public class WatchDir{

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    private final boolean recursive;
    private boolean trace = false;
    private Node node;
    private Path dir;

    /**
     * Creates a WatchService and registers the given directory
     */
    WatchDir(Path dir, boolean recursive, Node node) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey, Path>();
        this.recursive = recursive;
        this.node = node;
        this.dir = dir;


        if (recursive) {
            System.out.format("Scanning %s ...\n", dir);
            registerAll(dir);
            System.out.println("Done.");
        } else {
            register(dir);
            System.out.println("niet recursive watchdir gestart");
        }

        // enable trace after initial registration
        this.trace = true;
        processEvents();
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
        System.out.println("path registered");
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Since there is no option to get evens for existing files, this method allows you to
     * signal the existence of files to other classes (Node in our case)
     */
    private void signalExistingFiles(){
      File[] files = dir.toFile().listFiles();
        for (File file : files){
            if (file.isFile()){
                node.directoryChange(ENTRY_CREATE.name(),file.getName());
            }
        }
    }

    /**
     * Process all events for keys queued to the watcher
     */
    void processEvents() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("processing events");
                /*
                At first run the eventsprocessor should first check the already existing files in the directory.
                For Node the same action is taken as when a new file is created.
                However because there is no WatchEvent (already existing isn't really an event) for exisitng files
                the watchdir should send ENTRY_CREATE for every existing file to Node before starting to listen for events.
                 */
                signalExistingFiles();
                /*
                once this is done, the eventsprocessor can focus on events indefinitely.
                 */
                for (; ; ) {

                    // wait for key to be signalled
                    WatchKey key;
                    try {
                        key = watcher.take();
                    } catch (InterruptedException x) {
                        return;
                    }

                    Path dir = keys.get(key);
                    if (dir == null) {
                        System.err.println("WatchKey not recognized!");
                        continue;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind kind = event.kind();

                        // TBD - provide example of how OVERFLOW event is handled
                        if (kind == OVERFLOW) {
                            continue;
                        }

                        // Context for directory entry event is the file name of entry
                        WatchEvent<Path> ev = cast(event);
                        Path name = ev.context(); //filename
                        Path child = dir.resolve(name);

                        // print out event
                        System.out.format("%s: %s\n", event.kind().name(), child);

                        // Notify node of changes
                        node.directoryChange(event.kind().name(), name.getFileName().toString());

                        // if directory is created, and watching recursively, then
                        // register it and its sub-directories
                        if (recursive && (kind == ENTRY_CREATE)) {
                            try {
                                if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                                    registerAll(child);
                                }
                            } catch (IOException x) {
                                // ignore to keep sample readable
                            }
                        }
                    }

                    // reset key and remove from set if directory no longer accessible
                    boolean valid = key.reset();
                    if (!valid) {
                        keys.remove(key);

                        // all directories are inaccessible
                        if (keys.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        }).start();
    }
}