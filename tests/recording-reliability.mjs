import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'life-publication-'));
try{
  execFileSync('java',['-m','jdk.compiler/com.sun.tools.javac.Main','-d',tmp,'native/RecordingPublication.java','tests/RecordingPublicationTest.java'],{stdio:'inherit'});
  execFileSync('java',['-cp',tmp,'com.lifeblackbox.recorder.RecordingPublicationTest'],{stdio:'inherit'});
}finally{fs.rmSync(tmp,{recursive:true,force:true});}

