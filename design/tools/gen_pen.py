import json, os, subprocess, sys

THEMES={
 "dark": dict(BG="#121410",SURF="#1C1F1A",SURF2="#232820",LINE="#2E332B",
              GREEN="#7ADB4F",AMBER="#F2B84B",RED="#E5533D",TXT="#EDEFEA",SUB="#9AA096",
              BTN_FG="#0E120C",KNOB="#FFFFFF",
              TILES={
                "OSM":      {"base":"#171C15","alt":"#151A13","road":"#2A3324",
                             "park":"#1C2718","water":"#18231F"},
                "Google":   {"base":"#191D16","alt":"#171B14","road":"#333D2B",
                             "park":"#1E2919","water":"#192420"},
                "Satellite":{"base":"#131F18","alt":"#16241B","road":"#28382C",
                             "park":"#1A2E1F","water":"#142620"}}),
 "light": dict(BG="#F2F4EC",SURF="#FFFFFF",SURF2="#E9ECE0",LINE="#D3D8C6",
               GREEN="#3E8F23",AMBER="#A9741B",RED="#C43A24",TXT="#191C15",SUB="#5C6355",
               BTN_FG="#FFFFFF",KNOB="#FFFFFF",
               TILES={
                "OSM":      {"base":"#EFEBE1","alt":"#EAE6DB","road":"#FFFFFF",
                             "park":"#CBDCB4","water":"#A8CFDA"},
                "Google":   {"base":"#F5F2EA","alt":"#F0EDE4","road":"#FFFFFF",
                             "park":"#D2E0BB","water":"#ABD2DC"},
                "Satellite":{"base":"#26332A","alt":"#2B392F","road":"#46584A",
                             "park":"#1F3A26","water":"#1C3038"}}),
}
ATTR={"OSM":"© OpenStreetMap contributors","Google":"© Google","Satellite":"Imagery © mock"}

def apply_theme(name):
    g=globals()
    for k,v in THEMES[name].items():
        if k!="TILES": g[k]=v
    g["THEME"]=name

apply_theme("dark")
MONO="Roboto Mono"; SANS="Inter"
W,H=360,800
EXP="design/exports"

def t(content,size=14,color=TXT,mono=False,weight="400",width=None,growth=None,align="left"):
    n={"type":"text","content":content,"fontFamily":MONO if mono else SANS,
       "fontSize":size,"fontWeight":weight,"fill":color,"textAlign":align}
    if width: n["width"]=width
    if growth: n["textGrowth"]=growth
    return n

def ic(name,size,color,rot=None):
    return {"type":"icon","library":"Material Symbols Outlined","icon":name,
            "width":size,"height":size,"fill":color,**({"rotation":rot} if rot is not None else {})}

def fr(name,layout="vertical",w=None,h=None,fill=None,pad=None,gap=None,justify=None,
       align=None,radius=None,stroke=None,sw=None,kids=None,clip=False,id=None,opacity=None):
    n={"type":"frame","name":name,"layout":layout}
    if id: n["id"]=id
    if opacity is not None: n["opacity"]=opacity
    if w: n["width"]=w
    if h: n["height"]=h
    if fill: n["fill"]=fill
    if pad is not None: n["padding"]=pad
    if gap: n["gap"]=gap
    if justify: n["justifyContent"]=justify
    if align: n["alignItems"]=align
    if radius is not None: n["cornerRadius"]=radius
    if stroke:
        n["stroke"]=stroke; n["strokeWidth"]=sw or 1
    if kids is not None: n["children"]=kids
    if clip: n["clip"]=True
    return n

def abspos(node,x,y):
    node["layoutPosition"]="absolute"; node["x"]=x; node["y"]=y
    return node

def chip(label,color,icon_name=None):
    kids=[]
    if icon_name: kids.append(ic(icon_name,12,color))
    kids.append(t(label,11,color,mono=True))
    return fr("chip","horizontal",pad=[4,10],gap=4,align="center",radius=99,
              stroke=color,sw=1,kids=kids)

def dot(color):
    return {"type":"ellipse","width":8,"height":8,"fill":color}

def topbar(left_kids,right_kids):
    return fr("topbar","horizontal",w="fill_container",pad=[14,16],gap=8,align="center",
              justify="space_between",
              kids=[fr("l","horizontal",gap=8,align="center",kids=left_kids),
                    fr("r","horizontal",gap=8,align="center",kids=right_kids)])

def btn(label,outline=False,outline_color=RED):
    n=fr("btn","horizontal",w="fill_container",pad=[16,16],justify="center",align="center",
         radius=99,kids=[t(label,15,RED if outline else BTN_FG,weight="600")])
    if outline: n.update({"stroke":outline_color,"strokeWidth":1.5})
    else: n["fill"]=GREEN
    return n

def field(label,hint=None,icons=None,value=None,mono=False):
    row=[t(value or hint,13,SUB if not value else TXT,mono=mono,width="fill_container",
           growth="fixed-width")]
    for i in (icons or []): row.append(ic(i,18,SUB))
    return fr("field","vertical",w="fill_container",gap=6,kids=[
        t(label.upper(),10,SUB),
        fr("inbox","horizontal",w="fill_container",pad=[12,12],radius=10,
           stroke=LINE,sw=1,align="center",kids=row)])

def section(label):
    return t(label.upper(),10,SUB)

def nav(selected):
    def item(icon,label,active):
        c=GREEN if active else SUB
        return fr("ni","vertical",w=112,h=52,gap=3,justify="center",align="center",
                  kids=[ic(icon,22,c),t(label,10,c)])
    return fr("nav","horizontal",w=W,h=64,pad=[6,6],fill=SURF,justify="space_around",
              align="center",
              kids=[item("map","Map",selected=="map"),
                    item("groups","Members",selected=="members"),
                    item("hub","Networks",selected=="networks")])

def switch_on():
    return fr("swt","horizontal",w=40,h=22,radius=99,fill=GREEN,pad=[2,2],
              justify="end",align="center",kids=[{"type":"ellipse","width":18,"height":18,
                                                  "fill":KNOB}])

def s1():
    rows=[]
    for icon,title,desc in [
        ("location_on","GNSS position","To place you on the group map"),
        ("wifi","Nearby devices","WiFi Direct peer discovery"),
        ("bluetooth","Bluetooth","Peers and your LoRa node")]:
        rows.append(fr("row","horizontal",w="fill_container",pad=[16,16],gap=14,align="center",
            fill=SURF,radius=12,kids=[
              fr("ib","horizontal",w=44,h=44,fill=SURF2,radius=10,justify="center",align="center",
                 kids=[ic(icon,22,GREEN)]),
              fr("tx","vertical",w="fill_container",gap=4,kids=[
                 fr("tr","horizontal",w="fill_container",gap=8,align="center",
                    justify="space_between",kids=[t(title,15,TXT,weight="600"),
                                                  chip("required",AMBER)]),
                 t(desc,12,SUB,width="fill_container",growth="fixed-width")])]))
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[16,20],gap=14,kids=[
        fr("hero","vertical",w="fill_container",pad=[24,0],gap=6,align="center",
           kids=[t("find me in wood",20,TXT,weight="700"),
                 t("Ready for offline work",13,SUB)]),
        *rows,
        fr("sp","vertical",w="fill_container",h="fill_container"),
        btn("Grant permissions"),
        fr("why","horizontal",w="fill_container",justify="center",
           kids=[t("Why no internet?",12,SUB)])])
    return fr("S1 Permissions","vertical",id="s1",w=W,h=H,fill=BG,kids=[body])

def netcard(name,sub,subcolor,livecolor,seen):
    return fr("card","vertical",w="fill_container",pad=[14,16],gap=6,fill=SURF,radius=12,
        stroke=LINE,sw=1,kids=[
          fr("r1","horizontal",w="fill_container",gap=8,align="center",justify="space_between",
             kids=[fr("nm","horizontal",gap=8,align="center",
                      kids=[t(name,16,TXT,weight="600"),dot(livecolor)]),
                   ic("chevron_right",18,SUB)]),
          t(sub,12,subcolor,mono=True),
          fr("r3","horizontal",w="fill_container",justify="end",
             kids=[t(seen,10,SUB)])])

def s2():
    tb=topbar([t("Networks",16,TXT,weight="600")],
              [chip("2 links",GREEN,"lan"),ic("settings",20,SUB)])
    fab=abspos(fr("fab","horizontal",w=52,h=52,radius=99,fill=GREEN,justify="center",
                  align="center",kids=[ic("add",26,BTN_FG)]),292,664)
    return fr("S2 Networks","vertical",id="s2",w=W,h=H,fill=BG,kids=[
        tb,
        fr("list","vertical",w="fill_container",h="fill_container",pad=[8,16],gap=10,kids=[
            section("your networks"),
            netcard("hunt-2026","PRIVATE · owner you · 2 links",GREEN,GREEN,"updated 12 s ago"),
            netcard("mushroom-crew","OPEN · owner Olya · link down",RED,AMBER,"seen 42 min ago")]),
        abspos(fab,0,0) if False else nav("networks"),fab])

def s3():
    tb=topbar([ic("arrow_back",20,TXT),t("Create network",16,TXT,weight="600")],
              [chip("GPS ±4 m",GREEN,"satellite_alt")])
    secret=fr("secret","vertical",w="fill_container",pad=[14,16],gap=10,fill=SURF,radius=12,
        stroke=LINE,sw=1,kids=[
          fr("sh","horizontal",w="fill_container",justify="space_between",align="center",
             kids=[section("secret key"),
                   fr("acts","horizontal",gap=12,kids=[ic("refresh",18,SUB),ic("copy",18,SUB)])]),
          t("9f2k qv7m pzxd wtr5",20,GREEN,mono=True,weight="600"),
          t("Share name + key only in person.",11,SUB)])
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[16,20],gap=16,kids=[
        field("Network name","e.g. hunt-2026"),secret,
        fr("own","horizontal",w="fill_container",justify="space_between",align="center",
           kids=[t("Use my own passphrase instead",13,TXT),switch_on()]),
        section("join policy"),
        fr("pol","horizontal",w="fill_container",gap=4,pad=[3,3],fill=SURF,radius=10,
           kids=[seg("OPEN",False),seg("PRIVATE",True),seg("CODE",False)]),
        t("OPEN: anyone nearby joins · PRIVATE: you approve · CODE: hidden, code only",
          10,SUB,width="fill_container",growth="fixed-width"),
        fr("sp","vertical",w="fill_container",h="fill_container"),
        btn("Create network")])
    return fr("S3 Create","vertical",id="s3",w=W,h=H,fill=BG,kids=[tb,body])

def netdisc(name,owner,policy):
    pc = GREEN if policy=="PRIVATE" else AMBER
    return fr("disc","vertical",w="fill_container",pad=[12,14],gap=6,fill=SURF,radius=12,
        stroke=LINE,sw=1,kids=[
          fr("r1","horizontal",w="fill_container",gap=8,align="center",
             justify="space_between",kids=[
               fr("nm","horizontal",gap=8,align="center",
                  kids=[t(name,15,TXT,weight="600"),chip(policy,pc)]),
               ic("chevron_right",18,SUB)]),
          fr("r2","horizontal",w="fill_container",gap=6,align="center",
             kids=[ic("person",12,SUB),
                   t(f"owner {owner}",11,SUB,mono=True)])])

def s4():
    tb=topbar([ic("arrow_back",20,TXT),t("Join network",16,TXT,weight="600")],
              [chip("GPS —",AMBER,"satellite_alt")])
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[16,20],gap=12,kids=[
        fr("scanhdr","horizontal",w="fill_container",justify="space_between",align="center",
           kids=[section("nearby networks"),
                 fr("scan","horizontal",gap=4,align="center",
                    kids=[ic("radar",12,AMBER),t("scanning",10,AMBER)])]),
        fr("scanline","horizontal",w="fill_container",h=2,fill=AMBER,radius=99),
        netdisc("hunt-2026","Misha","PRIVATE"),
        netdisc("mushroom-crew","Olya","OPEN"),
        fr("div","horizontal",w="fill_container",gap=10,align="center",pad=[4,0],
           kids=[fr("l","horizontal",w="fill_container",h=1,fill=LINE),
                 t("or",10,SUB),
                 fr("r","horizontal",w="fill_container",h=1,fill=LINE)]),
        section("have a join code?"),
        field("name#secret#vtag","hunt-2026#9f2k…#wtr5",icons=["content_paste"],mono=True),
        fr("sp","vertical",w="fill_container",h="fill_container"),
        t("All traffic stays encrypted between members.",11,SUB),
        btn("Join with code")])
    return fr("S4 Join","vertical",id="s4",w=W,h=H,fill=BG,kids=[tb,body])

def tile_layer(src):
    pal=THEMES[THEME]["TILES"][src]
    out=[]
    for r in range(8):
        for c in range(3):
            out.append({"type":"rectangle","x":c*120,"y":r*100,"width":120,"height":100,
                        "fill":pal["base"] if (r+c)%2==0 else pal["alt"]})
    out.append({"type":"rectangle","x":-10,"y":297,"width":380,"height":6,   # road E-W
                "fill":pal["road"],"cornerRadius":3})
    out.append({"type":"rectangle","x":177,"y":-10,"width":6,"height":820,   # road N-S
                "fill":pal["road"],"cornerRadius":3})
    out.append({"type":"rectangle","x":252,"y":96,"width":64,"height":44,    # park patch
                "fill":pal["park"],"cornerRadius":8})
    out.append({"type":"rectangle","x":36,"y":496,"width":72,"height":52,
                "fill":pal["park"],"cornerRadius":8})
    out.append({"type":"ellipse","x":-30,"y":580,"width":150,"height":90,    # pond
                "fill":pal["water"]})
    return out

def s5(src="OSM"):
    tb=abspos(fr("maptb","horizontal",w=W,pad=[12,12],gap=8,align="center",
                 justify="space_between",kids=[chip("GPS ±4 m",GREEN,"satellite_alt"),
                                               chip("3 peers · 2 links",GREEN,"lan")]),0,0)
    def marker(x,y,label,color,halo=36,unloc=False):
        out=[]
        out.append({"type":"ellipse","x":x-halo//2,"y":y-halo//2,"width":halo,"height":halo,
                    "fill":{"type":"color","color":color+"26"}})
        out.append({"type":"ellipse","x":x-9,"y":y-9,"width":18,"height":18,"fill":color,
                    "stroke":{"type":"color","color":BG},"strokeWidth":2})
        lab=fr("lbl","horizontal",pad=[3,8],radius=8,fill=SURF,align="center",
               kids=[t(label,11,TXT,mono=True)])
        abspos(lab,x-len(label)*3.4-6,y+14); out.append(lab)
        if unloc:
            b=fr("badge","horizontal",pad=[3,7],radius=99,fill=SURF,stroke=AMBER,sw=1,gap=3,
                 align="center",kids=[ic("schedule",10,AMBER),t("no fix · 4 min",9,AMBER,mono=True)])
            abspos(b,x-24,y-34); out.append(b)
        return out
    def edge(p1,p2,label,color):
        (x1,y1),(x2,y2)=p1,p2
        minx,miny=min(x1,x2),min(y1,y2)
        w,h=max(abs(x2-x1),1),max(abs(y2-y1),1)
        line={"type":"path","x":minx,"y":miny,"width":w,"height":h,
              "viewBox":[minx,miny,w,h],
              "geometry":f"M {x1} {y1} L {x2} {y2}",
              "stroke":{"type":"color","color":color},"strokeWidth":2,
              "strokeLinecap":"round"}
        lab=fr("elbl","horizontal",pad=[2,7],radius=99,fill=SURF,stroke=color,sw=1,
               align="center",kids=[t(label,9,color,mono=True,weight="600")])
        abspos(lab,(x1+x2)//2-len(label)*2.6-4,(y1+y2)//2-10)
        return [line,lab]
    locb=abspos(fr("locb","horizontal",w=46,h=46,radius=99,fill=SURF,stroke=LINE,sw=1,
                   justify="center",align="center",kids=[ic("my_location",22,GREEN)]),300,652)
    cnt=abspos(fr("cnt","horizontal",pad=[10,12],radius=12,fill=SURF,stroke=LINE,sw=1,
                  align="center",gap=6,kids=[t("Members 4",12,TXT),dot(GREEN),
                                             t("located 3",12,SUB)]),12,706)
    attr=abspos(fr("attr","horizontal",pad=[3,8],radius=6,fill="#00000066",align="center",
                   kids=[t(ATTR[src],8,SUB)]),W-118,708)
    nv=abspos(nav("map"),0,736)
    kids=(tile_layer(src)
          +[*edge((150,330),(245,255),"LoRa",AMBER),
            *edge((150,330),(80,240),"Bluetooth","#6FA8DC")]
          +[*marker(150,330,"You",GREEN),*marker(245,255,"Misha","#E58C3D"),
            *marker(80,240,"Olya","#9B8CD9"),
            *marker(95,430,"Dana","#8FA88F",unloc=True),tb,locb,cnt,attr,nv])
    return fr(f"S5 Map · {src}","none",id=f"s5-{THEME}-{src.lower()}",w=W,h=H,
              fill=THEMES[THEME]["TILES"][src]["base"],clip=True,kids=kids)

def member(name,dist,brg,ang,live,edge=None,sub=None):
    right=(t(sub,10,AMBER,mono=True) if sub else
           fr("db","horizontal",gap=5,align="center",
              kids=[t(dist,13,TXT,mono=True),ic("navigation",12,GREEN,rot=ang),
                    t(brg,12,SUB,mono=True)]))
    mid_kids=[fr("nr","horizontal",w="fill_container",gap=7,align="center",
                 kids=[t(name,14,TXT,weight="600"),dot(GREEN if live else AMBER)]),
              fr("er2","horizontal",gap=4,align="center",
                 kids=[ic("link",10,SUB),
                       t(edge,10,SUB,mono=True)])] if edge else [
              fr("nr","horizontal",w="fill_container",gap=7,align="center",
                 kids=[t(name,14,TXT,weight="600"),dot(AMBER)])]
    if not live:
        mid_kids.append(t("tap to locate on map",10,SUB))
    return fr("mem","horizontal",w="fill_container",pad=[12,14],gap=12,align="center",
              fill=SURF,radius=12,stroke=LINE,sw=1,kids=[
        fr("av","horizontal",w=38,h=38,radius=99,fill=SURF2,justify="center",align="center",
           kids=[t(name[0],15,GREEN,weight="700")]),
        fr("mid","vertical",w="fill_container",gap=3,kids=mid_kids),
        right])

def s6():
    tb=topbar([ic("arrow_back",20,TXT),t("hunt-2026",16,TXT,weight="600")],
              [chip("GPS ±4 m",GREEN,"satellite_alt"),chip("2 links",GREEN,"lan")])
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[8,16],gap=10,kids=[
        member("Misha","1.2 km","NE 42°",315,True,edge="LoRa · direct"),
        member("Dana","","",0,False,edge="via Misha · LoRa",sub="unlocated · 4 min"),
        member("Olya","340 m","SW 218°",135,True,edge="Bluetooth · direct")])
    return fr("S6 Members","vertical",id="s6",w=W,h=H,fill=BG,kids=[tb,body,nav("members")])

def seg(label,active):
    n=fr("seg","horizontal",w="fill_container",h=34,justify="center",align="center",radius=8,
         kids=[t(label,11,BTN_FG if active else SUB,weight="600" if active else "400")])
    if active: n["fill"]=GREEN
    return n

def s7():
    tb=topbar([ic("arrow_back",20,TXT),t("hunt-2026",16,TXT,weight="600")],
              [chip("GPS ±4 m",GREEN,"satellite_alt"),chip("2 links",GREEN,"lan")])
    def mchip(name,active):
        c=GREEN if active else SUB
        return fr("mc","horizontal",pad=[6,10],gap=6,radius=99,align="center",
                  stroke=(GREEN if active else LINE),sw=(1.5 if active else 1),kids=[
            fr("mav","horizontal",w=22,h=22,radius=99,fill=SURF2,justify="center",
               align="center",kids=[t(name[0],11,c,weight="700")]),
            t(name,12,TXT if active else SUB,weight="600" if active else "400")])
    edge=fr("edge","vertical",w="fill_container",pad=[12,14],gap=10,fill=SURF,radius=12,
        stroke=LINE,sw=1,kids=[
          fr("er","horizontal",w="fill_container",justify="space_between",align="center",
             kids=[fr("el","horizontal",gap=6,align="center",
                      kids=[ic("link",15,GREEN),t("edge to Misha",13,TXT,weight="600")]),
                   t("direct",10,GREEN,mono=True)]),
          fr("segctl","horizontal",w="fill_container",gap=4,pad=[3,3],fill=SURF2,radius=10,
             kids=[seg("LoRa",True),seg("Bluetooth",False),seg("WiFi Direct",False)]),
          fr("meta","horizontal",gap=5,align="center",
             kids=[ic("schedule",11,SUB),
                   t("last seen 12 s ago · rssi -61 dBm",10,SUB,mono=True)])])
    conn=fr("conn","vertical",w="fill_container",gap=10,kids=[
        section("connections"),
        fr("mrow","horizontal",w="fill_container",gap=8,
           kids=[mchip("Misha",True),mchip("Dana",False),mchip("Olya",False)]),
        edge,
        t("Agree by phone, then both pick the same transport. If a link dies, both phones\nauto-try their other transports.",
          10,SUB,width="fill_container",growth="fixed-width")])
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[12,16],gap=12,kids=[
        conn,
        section("lora node"),
        fr("node","horizontal",w="fill_container",pad=[10,12],gap=10,align="center",fill=SURF,
           radius=12,stroke=LINE,sw=1,kids=[
             ic("cell_tower",18,GREEN),
             fr("nt","vertical",w="fill_container",gap=2,
                kids=[t("TTGO-TBeam-1",12,TXT,mono=True),t("ready",10,GREEN)]),
             ic("chevron_right",16,SUB)]),
        fr("viber","horizontal",w="fill_container",pad=[10,12],gap=10,align="center",fill=SURF,
           radius=12,stroke=LINE,sw=1,kids=[
             ic("share",18,GREEN),
             fr("vt","vertical",w="fill_container",gap=2,
                kids=[t("Internet relay · Viber",12,TXT),
                      t("share position when online",10,SUB)]),
             ic("chevron_right",16,SUB)]),
        fr("shr","horizontal",w="fill_container",justify="space_between",align="center",
           kids=[fr("st","vertical",w="fill_container",gap=2,
                    kids=[t("Broadcast my position",13,TXT),
                          t("Network setting · keep-alives continue when off",10,SUB)]),
                switch_on()]),
        section("diagnostics"),
        fr("diag","vertical",w="fill_container",pad=[10,12],gap=5,fill=SURF,radius=12,
           stroke=LINE,sw=1,kids=[
             t("sent 214 · rx 198 · relayed 37",11,TXT,mono=True),
             t("auth-failed 0 · dropped 1",11,AMBER,mono=True)]),
        btn("Leave network",outline=True)])
    return fr("S7 Detail","vertical",id="s7",w=W,h=H,fill=BG,kids=[tb,body])

def s9():
    tb=topbar([ic("arrow_back",20,TXT),t("Settings",16,TXT,weight="600")],
              [chip("GPS —",AMBER,"satellite_alt")])
    def row(label,hint=None,value=None):
        kids=[fr("rl","vertical",w="fill_container",gap=2,
                 kids=[t(label,13,TXT)]+([t(hint,10,SUB)] if hint else [])),
              t(value,12,GREEN if value else SUB,mono=bool(value))]
        return fr("row","horizontal",w="fill_container",pad=[12,14],align="center",
                  justify="space_between",fill=SURF,radius=12,stroke=LINE,sw=1,kids=kids)
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[12,16],gap=12,kids=[
        section("appearance"),
        fr("theme","horizontal",w="fill_container",gap=4,pad=[3,3],fill=SURF,radius=10,
           kids=[seg("Dark",True),seg("Light",False),seg("System",False)]),
        section("map tiles"),
        fr("tiles","horizontal",w="fill_container",gap=4,pad=[3,3],fill=SURF,radius=10,
           kids=[seg("OSM",True),seg("Google",False),seg("Sat",False)]),
        t("Tiles cached offline; attribution shown on map.",10,SUB,
          width="fill_container",growth="fixed-width"),
        section("defaults for new networks"),
        row("Beacon interval","position update cadence","30 s"),
        row("Join policy","for discovered joins","PRIVATE"),
        section("about"),
        row("find me in wood","offline-first · no internet permission","v0.1")])
    return fr("S9 Settings","vertical",id="s9",w=W,h=H,fill=BG,kids=[tb,body])

def s8():
    rows=[]
    for name,rssi,last in [("TBeam-A3F2","-61 dBm",True),("TBeam-B771","-74 dBm",False),
                           ("Heltec-LOLA","-81 dBm",False),("RAK4631","-88 dBm",False)]:
        rkids=[ic("signal_cellular_alt",18,SUB),t(name,13,TXT,mono=True)]
        if last:
            rkids.append(fr("tag","horizontal",pad=[2,6],radius=99,fill=SURF2,gap=3,align="center",
                            kids=[ic("star",14,AMBER),t("last used",10,AMBER)]))
        rkids.append(fr("sp","vertical",w="fill_container",h=1))
        rkids.append(t(rssi,11,SUB,mono=True))
        rows.append(fr("dev","horizontal",w="fill_container",pad=[12,14],gap=10,align="center",
                       fill=SURF,radius=10,stroke=(AMBER if last else LINE),sw=1,kids=rkids))
    sheet=abspos(fr("sheet","vertical",w=W,pad=[10,16,20,16],gap=12,fill=SURF,
                    radius=[20,20,0,0],kids=[
        fr("grab","horizontal",w=44,h=4,radius=99,fill=LINE),
        fr("ht","vertical",w="fill_container",gap=4,kids=[
            t("Choose your LoRa node",16,TXT,weight="600"),
            t("The phone talks to this device over Bluetooth LE or USB; nodes handle the rest.",
              11,SUB,width="fill_container",growth="fixed-width")]),
        fr("linktype","horizontal",w="fill_container",gap=4,pad=[3,3],fill=SURF2,radius=10,
           kids=[seg("BLE",True),seg("USB",False)]),
        fr("scanline","horizontal",w="fill_container",h=2,fill=AMBER,radius=99),
        *rows,
        fr("usbhint","horizontal",w="fill_container",gap=6,align="center",
           kids=[ic("usb",14,SUB),t("USB: CH340 / CP210x / FTDI / CDC-ACM",10,SUB,mono=True)]),
        fr("scn","horizontal",w="fill_container",pad=[14,12],justify="center",align="center",
           radius=99,kids=[t("Scan again",13,SUB,weight="600")]),
        btn("Use selected node")]),0,340)
    return fr("S8 NodePicker","none",id="s8",w=W,h=H,fill=BG+"CC",clip=True,kids=[sheet])

def s10():
    tb=topbar([ic("arrow_back",20,TXT),t("Join hunt-2026",16,TXT,weight="600")],
              [chip("GPS —",AMBER,"satellite_alt")])
    def step(icon,label,state):
        c={"done":GREEN,"active":AMBER,"todo":SUB}[state]
        mark=ic("check_circle",16,GREEN) if state=="done" else \
             (ic("radar",16,AMBER) if state=="active" else
              {"type":"ellipse","width":12,"height":12,
               "stroke":{"type":"color","color":LINE},"strokeWidth":1.5})
        return fr("st","horizontal",w="fill_container",gap=10,align="center",
                  opacity=1 if state!="todo" else 0.55,
                  kids=[mark,t(label,12,TXT if state!="todo" else SUB,
                               weight="600" if state=="active" else "400")])
    prog=fr("prog","vertical",w="fill_container",pad=[14,16],gap=12,fill=SURF,radius=12,
        stroke=LINE,sw=1,kids=[
          step("c","Request sent to owner","done"),
          step("r","Waiting for owner…","active"),
          step("s","Compare 6-char code","todo"),
          step("k","Connected","todo")])
    sas=fr("sas","vertical",w="fill_container",pad=[16,16],gap=10,fill=SURF,radius=12,
        stroke=AMBER,sw=1,kids=[
          fr("sh","horizontal",w="fill_container",justify="space_between",align="center",
             kids=[section("verify code"),ic("shield",14,AMBER)]),
          t("4F2K-Q7MZ",28,GREEN,mono=True,weight="700"),
          t("Show this to the network owner. Accept only if the codes match on both screens.",
            11,SUB,width="fill_container",growth="fixed-width"),
          fr("br","horizontal",w="fill_container",gap=10,kids=[
            fr("no","horizontal",w="fill_container",pad=[12,12],justify="center",align="center",
               radius=99,stroke=RED,sw=1.5,kids=[t("Mismatch",13,RED,weight="600")]),
            btn("Codes match")])])
    body=fr("body","vertical",w="fill_container",h="fill_container",pad=[16,20],gap=14,kids=[
        prog,sas,
        fr("sp","vertical",w="fill_container",h="fill_container"),
        t("PRIVATE network · owner Misha must accept your request.",10,SUB)])
    return fr("S10 JoinSAS","vertical",id="s10",w=W,h=H,fill=BG,kids=[tb,body])

def s11():
    def mchip(name,active):
        c=GREEN if active else SUB
        return fr("mc","horizontal",pad=[6,10],gap=6,radius=99,align="center",
                  stroke=(GREEN if active else LINE),sw=(1.5 if active else 1),kids=[
            fr("mav","horizontal",w=22,h=22,radius=99,fill=SURF2,justify="center",
               align="center",kids=[t(name[0],11,c,weight="700")]),
            t(name,12,TXT if active else SUB)])
    card=fr("card","vertical",w=W-32,pad=[18,18],gap=12,fill=SURF,radius=16,kids=[
        fr("hdr","horizontal",w="fill_container",gap=12,align="center",kids=[
            fr("av","horizontal",w=44,h=44,radius=99,fill=SURF2,justify="center",
               align="center",kids=[t("D",18,GREEN,weight="700")]),
            fr("ht","vertical",w="fill_container",gap=3,kids=[
                t("Dana asks to join",15,TXT,weight="600"),
                t("hunt-2026 · via Bluetooth",11,SUB,mono=True)])]),
        fr("sas","horizontal",w="fill_container",pad=[12,12],radius=10,fill=SURF2,
           justify="center",align="center",gap=8,
           kids=[ic("shield",14,AMBER),t("4F2K-Q7MZ",22,GREEN,mono=True,weight="700")]),
        t("Compare with Dana's screen before accepting. No way to verify (remote, no voice)?\nThen a MITM may have captured the secret.",
          10,SUB,width="fill_container",growth="fixed-width"),
        fr("br","horizontal",w="fill_container",gap=10,kids=[
            fr("no","horizontal",w="fill_container",pad=[12,12],justify="center",align="center",
               radius=99,stroke=RED,sw=1.5,kids=[t("Reject",13,RED,weight="600")]),
            btn("Accept")]),
        fr("uv","horizontal",w="fill_container",justify="center",
           kids=[t("Accept unverified (unsafe)",11,AMBER)])])
    abspos(card,16,300)
    return fr("S11 OwnerAccept","none",id="s11",w=W,h=H,fill=BG+"CC",clip=True,kids=[
        abspos(fr("bglist","vertical",w=W,pad=[12,16],gap=10,opacity=0.35,kids=[
            member("Misha","1.2 km","NE 42°",315,True,edge="LoRa · direct"),
            member("Olya","340 m","SW 218°",135,True,edge="Bluetooth · direct")]),0,60),
        card])

SCREENS={"s1-permissions":s1,"s2-networks":s2,"s3-create":s3,"s4-join":s4,
         "s5-map":s5,"s6-members":s6,"s7-detail":s7,"s8-node-picker":s8,
         "s9-settings":s9,"s10-join-sas":s10,"s11-owner-accept":s11}

def repl_and_run(trees,out_pen,exports):
    js=";".join(f'rt{i}=Insert(document,{json.dumps(tr,ensure_ascii=False)})'
                for i,tr in enumerate(trees))
    esc=js.replace("\\","\\\\").replace('"','\\"')
    exs=";".join(f'Export([rt{i}], \\"png\\", \\"{os.path.abspath(d)}\\")'
                 for i,d in enumerate(exports))
    for d in exports: os.makedirs(os.path.abspath(d),exist_ok=True)
    script=(f'execute({{ input: "{esc}" }})\n'
            f'execute({{ input: "{exs}" }})\n'
            "save()\nexit()\n")
    rp=out_pen+".repl"
    open(rp,"w").write(script)
    r=subprocess.run(["pen","interactive","-o",out_pen],stdin=open(rp),
                     capture_output=True,text=True,timeout=300)
    log=r.stdout+r.stderr
    ok=all(any(f"Exported" in l and os.path.basename(os.path.abspath(d)) in l
               for l in log.splitlines()) for d in exports)
    print(("OK  " if ok else "FAIL")+" "+out_pen,flush=True)
    if not ok:
        print("\n".join(l for l in log.splitlines() if "rror" in l)[:2000]); sys.exit(1)

if __name__=="__main__":
    only=sys.argv[1:] or list(SCREENS)
    os.makedirs(EXP,exist_ok=True)
    for theme in ("dark","light"):
        apply_theme(theme)
        suf="" if theme=="dark" else "-light"
        for name in only:
            if name=="s5-map":
                trees=[s5(s) for s in ATTR]
                repl_and_run(trees,f"design/{name}{suf}.pen",
                             [f"{EXP}/{name}-{s.lower()}{suf}" for s in ATTR])
            else:
                repl_and_run([SCREENS[name]()],f"design/{name}{suf}.pen",
                             [f"{EXP}/{name}{suf}"])
