package com.lifeblackbox.recorder;
public final class TimelineSegmentsTest {
 public static void main(String[] args){
  TimelineSegments t=new TimelineSegments();
  if(!t.flush().isEmpty())throw new AssertionError("empty flush");
  t.accept(0,50,1000);t.accept(50,50,1050);
  String first=t.accept(100,50,5000);
  if(!first.contains("\"from\":0")||!first.contains("\"to\":100")||!first.contains("\"end\":1100"))throw new AssertionError(first);
  String second=t.flush();if(!second.contains("\"from\":100")||!second.contains("\"start\":5000"))throw new AssertionError(second);
  for(int i=0;i<=200;i++){String s=t.accept(i*50,50,10000+i*50);if(i==200&&s.isEmpty())throw new AssertionError("10 second checkpoint");}
  if(!t.flush().contains("\"from\":10000"))throw new AssertionError("continuous offset");
  System.out.println("PCM offset / silence gap / continuous spans / checkpoint / empty flush: PASS");
 }
}
