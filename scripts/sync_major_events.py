#!/usr/bin/env python3
import hashlib, html, json, re, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

OUT = Path('data/events-feed.json')
UA = 'GameDrop-EventSync/1.0'

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode('utf-8', errors='replace')

def text(raw):
    raw = re.sub(r'<script\b[^>]*>.*?</script>', ' ', raw, flags=re.I|re.S)
    raw = re.sub(r'<style\b[^>]*>.*?</style>', ' ', raw, flags=re.I|re.S)
    raw = re.sub(r'<[^>]+>', ' ', raw)
    return re.sub(r'\s+', ' ', html.unescape(raw)).strip()

def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace('+00:00','Z')

def eid(name, start):
    return int(hashlib.sha1(f'{name.lower()}|{start[:10]}'.encode()).hexdigest()[:12],16)

def make(name,start,desc,url,tz,end=None):
    return {'id':eid(name,start),'name':name,'description':desc,'startTime':start,'endTime':end,'timeZone':tz,'liveStreamUrl':url,'logoUrl':None,'games':[],'videos':[]}

def merge(feed, additions):
    rows=feed.get('events',[])
    keys={(re.sub(r'\W+','',(x.get('name') or '').lower()),(x.get('startTime') or '')[:10]) for x in rows}
    for x in additions:
        k=(re.sub(r'\W+','',x['name'].lower()),x['startTime'][:10])
        if k not in keys:
            rows.append(x); keys.add(k)
    rows.sort(key=lambda x:x.get('startTime',''))
    feed['events']=rows
    return feed

def gamescom():
    url='https://www.gamescom.global/en/live/events'
    s=text(fetch(url))
    out=[]
    # Header form seen on the official site: #gamescom 26.-30.08.2026 gamescom ONL: 25.08.2026
    m=re.search(r'gamescom\s+(\d{1,2})\.?\s*[-–]\s*(\d{1,2})\.?(\d{2})\.?(20\d{2})',s,re.I)
    if not m:
        m=re.search(r'gamescom\s+(20\d{2}).{0,250}?August\s+(\d{1,2})\s+to\s+(\d{1,2})',s,re.I)
        if m:
            year,d1,d2=int(m.group(1)),int(m.group(2)),int(m.group(3))
        else:
            year=d1=d2=None
    else:
        d1,d2,month,year=int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))
    if year:
        st=datetime(year,8,d1,10,0,tzinfo=ZoneInfo('Europe/Berlin'))
        en=datetime(year,8,d2,20,0,tzinfo=ZoneInfo('Europe/Berlin'))
        out.append(make(f'gamescom {year}',iso(st),'Feira anual oficial de games em Colônia.',url,'Europe/Berlin',iso(en)))
    onl=re.search(r'(?:gamescom\s+ONL\s*:\s*|Opening Night Live.{0,120}?)(\d{1,2})\.?(?:08|August)?\.?(20\d{2})?',s,re.I)
    if onl:
        day=int(onl.group(1)); y=int(onl.group(2) or year or datetime.now().year)
        st=datetime(y,8,day,20,0,tzinfo=ZoneInfo('Europe/Berlin'))
        out.append(make(f'gamescom Opening Night Live {y}',iso(st),'Show de abertura oficial da gamescom, com anúncios e world premieres.',url,'Europe/Berlin'))
    return out

def tga():
    url='https://thegameawards.com/faq'
    s=text(fetch(url))
    date=re.search(r'December\s+(\d{1,2}),\s*(20\d{2})',s,re.I)
    clock=re.search(r'(\d{1,2})(?::(\d{2}))?\s*p(?:\.?m\.?)?\s*ET',s,re.I)
    if not date: return []
    day,year=int(date.group(1)),int(date.group(2))
    hour,minute=(int(clock.group(1)),int(clock.group(2) or 0)) if clock else (19,30)
    if clock and hour!=12: hour+=12
    st=datetime(year,12,day,hour,minute,tzinfo=ZoneInfo('America/New_York'))
    return [make(f'The Game Awards {year}',iso(st),'Premiação anual com anúncios, trailers e world premieres.',url,'America/New_York')]

def main():
    feed=json.loads(OUT.read_text(encoding='utf-8'))
    additions=[]; status=[]
    for name,fn in [('gamescom',gamescom),('The Game Awards',tga)]:
        try:
            rows=fn(); additions.extend(rows); status.append(f'{name}:{len(rows)}')
        except Exception as e:
            status.append(f'{name}:error'); print(name,e)
    feed=merge(feed,additions)
    feed['sourcesStatusMajor']=status
    feed['generatedAt']=datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
    OUT.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('major sources',status,'total',len(feed.get('events',[])))
if __name__=='__main__': main()
