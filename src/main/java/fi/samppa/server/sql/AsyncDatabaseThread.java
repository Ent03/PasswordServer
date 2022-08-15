package fi.samppa.server.sql;


import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncDatabaseThread {
    private static final HashMap<Class<? extends SQLStorage>, AsyncDatabaseThread> handlers = new HashMap<>();

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();


    public AsyncDatabaseThread(SQLStorage storage){

        handlers.put(storage.getClass(), this);
        start();
    }

    public synchronized void newTask(boolean async, Runnable runnable){
        if(!async){
            runnable.run();
            return;
        }
        tasks.add(runnable);
    }

    public synchronized void newTask(Runnable runnable){
        newTask(true, runnable);
    }

    private void updateQueue(){
        while (!tasks.isEmpty()){
            Runnable runnable = tasks.peek();
            runnable.run();
            tasks.poll();
        }
    }

    public void start(){
        //25ms max interval between pushes
        new Timer(true).schedule(new TimerTask() {
            public void run() {
                updateQueue();
            }
        },0L, 25L);
    }

    public LinkedBlockingQueue<Runnable> getTasks() {
        return tasks;
    }

    public static int getInLine(){
        AtomicInteger total = new AtomicInteger();
        handlers.values().forEach(h -> total.addAndGet(h.getTasks().size()));
        return total.get();
    }

    public static boolean isEmpty(){
        return getInLine() == 0;
    }
}
