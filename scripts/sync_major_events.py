#!/usr/bin/env python3
import hashlib, html, json, re, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

OUT = Path('data/events-feed.json')
UA = 'Mozilla/5.0 GameDrop-EventSync/1.1'

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'en-US,en;q=0.9'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode('utf-8', errors='replace')

def text(raw):
    raw = re.sub(r'<script\b[^>]*>.*?</script>', ' ', raw, flags=re.I|re.S)
    raw = re.sub(r'<style\b[^>]*>.*?</style>', ' ', raw, flags=re.I|re.S)
    raw = re.sub(r'<[^>]+>', ' ', raw)
    return re.sub(r'\s+', ' ', html.unescape(raw)).strip()

def iso(dt): return dt.astimezone(timezone.utc).isoformat().replace('+00:00','Z')
def eid(name,start): return int(hashlib.sha1(f'{name.lower()}|{start[:10]}'.encode()).hexdigest()[:12],16)
def make(name,start,desc,url,tz,end=None):
    return {'id':eid(name,start),'name':name,'description':desc,'startTime':start,'endTime':end,'timeZone':tz,'liveStreamUrl':url,'logoUrl':None,'games':[],'videos':[]}

def merge(feed, additions):
    rows=feed.get('events',[])
    keys={(re.sub(r'\W+','',(x.get('name') or '').lower()),(x.get('startTime') or '')[:10]) for x in rows}
    for x in additions:
        k=(re.sub(r'\W+','',x['name'].lower()),x['startTime'][:10])
        if k not in keys: rows.append(x); keys.add(k)
    rows.sort(key=lambda x:x.get('startTime','')); feed['events']=rows; return feed

def gamescom():
    urls=['https://www.gamescom.global/en/newsletter','https://www.gamescom.global/en/signing-events','https://www.gamescom.global/en/live/events']
    s=''; source=urls[0]
    for url in urls:
        try:
            candidate=text(fetch(url))
            if candidate:
                s += ' ' + candidate
                source=url
        except Exception as e:
            print('gamescom source failed',url,e)
    out=[]; year=d1=d2=None
    m=re.search(r'gamescom\s+(\d{1,2})\.?\s*[-–]\s*(\d{1,2})\.\s*08\.\s*(20\d{2})',s,re.I)
    if m:
        d1,d2,year=map(int,m.groups())
    if not year:
        m=re.search(r'gamescom\s+(20\d{2}).{0,300}?(?:from\s+)?August\s+(\d{1,2})\s+(?:to|[-–])\s+(\d{1,2})',s,re.I)
        if m: year,d1,d2=map(int,m.groups())
    if year:
        st=datetime(year,8,d1,10,0,tzinfo=ZoneInfo('Europe/Berlin')); en=datetime(year,8,d2,20,0,tzinfo=ZoneInfo('Europe/Berlin'))
        out.append(make(f'gamescom {year}',iso(st),'Feira anual oficial de games em Colônia.',source,'Europe/Berlin',iso(en)))
    onl=re.search(r'(?:gamescom\s+ONL\s*:\s*|Opening Night Live.{0,180}?)(\d{1,2})\.\s*08\.\s*(20\d{2})',s,re.I)
    if not onl:
        onl=re.search(r'Opening Night Live.{0,180}?August\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?',s,re.I)
    if onl:
        day=int(onl.group(1)); y=int(onl.group(2) or year or datetime.now().year)
        st=datetime(y,8,day,20,0,tzinfo=ZoneInfo('Europe/Berlin'))
        out.append(make(f'gamescom Opening Night Live {y}',iso(st),'Show de abertura oficial da gamescom, com anúncios e world premieres.',source,'Europe/Berlin'))
    return out

def tga():
    urls=['https://thegameawards.com/news/tga-returns-december-10-2026','https://thegameawards.com/faq']
    s=''; source=urls[0]
    for url in urls:
        try:
            candidate=text(fetch(url)); s += ' ' + candidate; source=url
        except Exception as e: print('TGA source failed',url,e)
    date=re.search(r'December\s+(\d{1,2}),\s*(20\d{2})',s,re.I)
    if not date: return []
    day,year=int(date.group(1)),int(date.group(2))
    clock=re.search(r'(\d{1,2})(?::(\d{2}))?\s*p(?:\.?m\.?)?\s*ET',s,re.I)
    hour,minute=(int(clock.group(1)),int(clock.group(2) or 0)) if clock else (19,30)
    if clock and hour!=12: hour+=12
    st=datetime(year,12,day,hour,minute,tzinfo=ZoneInfo('America/New_York'))
    return [make(f'The Game Awards {year}',iso(st),'Premiação anual com anúncios, trailers e world premieres.',source,'America/New_York')]

def main():
    feed=json.loads(OUT.read_text(encoding='utf-8')); additions=[]; status=[]
    for name,fn in [('gamescom',gamescom),('The Game Awards',tga)]:
        try:
            rows=fn(); additions.extend(rows); status.append(f'{name}:{len(rows)}')
        except Exception as e:
            status.append(f'{name}:error'); print(name,e)
    feed=merge(feed,additions); feed['sourcesStatusMajor']=status; feed['generatedAt']=datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
    OUT.write_text(json.dumps(feed,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('major sources',status,'total',len(feed.get('events',[])))
if __name__=='__main__': main()
