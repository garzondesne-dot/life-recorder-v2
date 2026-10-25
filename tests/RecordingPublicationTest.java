package com.lifeblackbox.recorder;

/** Executes the production coordinator with durable fake storage and injected process death. */
public class RecordingPublicationTest {
    static class Disk implements RecordingPublication.Backend {
        boolean done, intent, pending, exists, indexed, cleaned;
        String uri; int creates,writes,indexes,step,crash;
        Disk(int crash){this.crash=crash;}
        void checkpoint(){if(++step==crash)throw new RuntimeException("process died");}
        public boolean done(){return done;}
        public String destination(){return uri;}
        public void prepare(){intent=true;checkpoint();}
        public RecordingPublication.Target find(){return exists?new RecordingPublication.Target("content://audio/1",pending):null;}
        public RecordingPublication.Target inspect(String u){return find();}
        public RecordingPublication.Target create(){if(!intent)throw new AssertionError("missing intent");creates++;exists=true;pending=true;checkpoint();return find();}
        public void remember(String u){uri=u;checkpoint();}
        public void writePending(RecordingPublication.Target t){if(!pending)throw new AssertionError("published audio overwritten");writes++;checkpoint();}
        public void finish(RecordingPublication.Target t){pending=false;checkpoint();}
        public void index(RecordingPublication.Target t){if(!indexed)indexes++;indexed=true;checkpoint();}
        public void complete(){done=true;checkpoint();}
        public void cleanup(){cleaned=true;checkpoint();}
    }
    public static void main(String[] args)throws Exception{
        for(int death=1;death<=8;death++){
            Disk disk=new Disk(death);
            try{RecordingPublication.publish(disk);}catch(RuntimeException expected){}
            int writes=disk.writes;boolean wasPublished=disk.exists&&!disk.pending;
            disk.crash=0;
            RecordingPublication.publish(disk);
            RecordingPublication.publish(disk);
            if(disk.creates!=1||disk.indexes!=1||!disk.done||!disk.cleaned)throw new AssertionError("duplicate after crash "+death);
            if(wasPublished&&disk.writes!=writes)throw new AssertionError("overwritten after crash "+death);
        }
        Disk deleted=new Disk(0);deleted.uri="content://audio/1";
        try{RecordingPublication.publish(deleted);throw new AssertionError("missing destination recreated");}
        catch(java.io.IOException expected){}
        if(deleted.creates!=0)throw new AssertionError("recreated deleted recording");
        Disk complete=new Disk(0);complete.done=true;
        RecordingPublication.publish(complete);
        if(complete.creates!=0||complete.indexed)throw new AssertionError("completed receipt republished");
        System.out.println("Publication crash boundaries / idempotent retry / no published overwrite / missing destination: PASS");
    }
}

