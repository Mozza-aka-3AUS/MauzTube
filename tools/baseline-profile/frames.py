# Analizza `dumpsys gfxinfo <pkg> framestats`: tempi per fase (mediana / 90° percentile).
import sys,statistics
lines=open(sys.argv[1]).read().splitlines()
hdr=None; rows=[]
for l in lines:
    if l.startswith('Flags,'): hdr=l.strip().rstrip(',').split(','); continue
    if hdr and l and l[0].isdigit():
        v=l.strip().rstrip(',').split(',')
        if len(v)>=len(hdr): rows.append(dict(zip(hdr,map(int,v[:len(hdr)]))))
    if l.startswith('---PROFILEDATA---') and rows: pass
rows=[r for r in rows if r['Flags']==0]
def ms(a,b): return [(r[b]-r[a])/1e6 for r in rows if r[a]>0 and r[b]>0]
phases={
 'input+animazione':('HandleInputStart','PerformTraversalsStart'),
 'layout+disegno UI (Compose)':('PerformTraversalsStart','SyncQueued'),
 'thread di rendering':('SyncStart','SwapBuffers'),
 'attesa GPU':('SwapBuffers','GpuCompleted'),
 'totale fotogramma':('IntendedVsync','FrameCompleted'),
}
print(f"fotogrammi: {len(rows)}")
for k,(a,b) in phases.items():
    xs=sorted(ms(a,b))
    if xs: print(f"  {k:32s} mediana {statistics.median(xs):6.1f} ms   p90 {xs[int(len(xs)*0.9)-1]:6.1f} ms")
