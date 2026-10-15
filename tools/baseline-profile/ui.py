# Trova un elemento nell'interfaccia (uiautomator) per testo o content-desc e ne stampa il centro.
import re,sys,subprocess,time
adb=sys.argv[1]; key=sys.argv[2]; timeout=float(sys.argv[3]) if len(sys.argv)>3 else 10
end=time.time()+timeout
while time.time()<end:
    subprocess.run([adb,"shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True)
    xml=subprocess.run([adb,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True).stdout
    for node in re.findall(r'<node [^>]*>',xml):
        t=re.search(r' text="([^"]*)"',node).group(1) if ' text="' in node else ''
        d=re.search(r' content-desc="([^"]*)"',node).group(1) if 'content-desc="' in node else ''
        if key==t or key==d:
            b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
            x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); sys.exit(0)
    time.sleep(1)
sys.exit(1)
