from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import csv,math,json,sys
root=Path(__file__).resolve().parent/'plugins/RoadProbe'/('seed-'+(sys.argv[1] if len(sys.argv)>1 else '12345'))
rows=list(csv.DictReader((root/'surface.csv').open(encoding='utf-8')))
roads=list(csv.DictReader((root/'roads.csv').open(encoding='utf-8')))
walk=list(csv.DictReader((root/'walk-collision.csv').open(encoding='utf-8')))
surface={(int(r['x']),int(r['z'])):r for r in rows}
ground_rows=list(csv.DictReader((root/'ground.csv').open(encoding='utf-8'))) if (root/'ground.csv').exists() else rows
ground={(int(r['x']),int(r['z'])):r for r in ground_rows}
voxels=list(csv.DictReader((root/'voxels.csv').open(encoding='utf-8'))) if (root/'voxels.csv').exists() else []
roadmap={(int(r['x']),int(r['z'])):r for r in roads}
curves=list(csv.DictReader((root/'curves.csv').open(encoding='utf-8')))
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
colors={'GRASS_BLOCK':(102,143,65),'DIRT':(124,93,59),'WATER':(60,121,178),'SAND':(210,198,142),'STONE':(147,150,150),'COBBLESTONE':(121,126,128),'GLASS':(184,225,232),'STONE_BRICKS':(155,159,161)}
def color(material):
 if material in colors:return colors[material]
 if 'LEAVES' in material:return (60,111,52)
 if 'LOG' in material or 'WOOD' in material:return (137,111,73)
 if 'OAK' in material:return (171,139,82)
 if 'STONE' in material or 'ANDESITE' in material or 'DIORITE' in material:return (147,151,154)
 if 'GRASS' in material or 'FERN' in material:return (91,137,59)
 if 'SNOW' in material:return (232,237,240)
 if 'ICE' in material:return (162,204,226)
 if 'FLOWER' in material or 'POPPY' in material:return (136,147,67)
 return (134,143,113)
# Diagnostic metrics come from the real server collision shapes, not from the renderer.
cliffs=[r for r in walk if math.isfinite(float(r['step'])) and abs(float(r['step']))>.60001]
missing=[r for r in walk if not math.isfinite(float(r['feetY']))]
metrics={'source':'Paper 1.16.5 build 794 exported real blocks and NMS collision shapes','road_blocks':len(roads),'sampled_foot_positions':len(walk),'steps_over_0_6':len(cliffs),'missing_walk_surface':len(missing),'max_abs_step':max(abs(float(r['step'])) for r in walk if math.isfinite(float(r['step']))),'cliff_examples':cliffs[:20]}
progress=[]
for name in set(r['road'] for r in curves):
 line=[r for r in curves if r['road']==name];ax,az=float(line[0]['x']),float(line[0]['z']);dx,dz=float(line[-1]['x'])-ax,float(line[-1]['z'])-az;length=math.hypot(dx,dz)
 for a,b in zip(line,line[1:]):progress.append(((float(b['x'])-float(a['x']))*dx+(float(b['z'])-float(a['z']))*dz)/length)
metrics['backtracking_segments']=sum(p<-.02 for p in progress)
metrics['min_forward_step']=min(progress)
(root/'collision-report.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(metrics,ensure_ascii=False))
# Top view: actual block types, with elevation shading.
img=Image.new('RGB',(1510,1530),(23,31,38));d=ImageDraw.Draw(img)
d.text((25,15),'Paper 1.16.5 实际生成结果 · 方块俯视图',font=font,fill=(236,240,244))
d.text((25,48),'来自真实世界导出的方块；不是游戏客户端截图。绿色=地形，灰色=路面，浅蓝=玻璃。',font=small,fill=(174,190,200))
for (x,z),r in surface.items():
 c=color(r['material']);h=int(r['y']);shade=max(.70,min(1.2,1+(h-70)*.007));c=tuple(min(255,int(v*shade)) for v in c)
 px=55+(x+350)*2;py=90+(z+350)*2;d.rectangle((px,py,px+1,py+1),fill=c)
for (x,z),r in roadmap.items():
 px=55+(x+350)*2;py=90+(z+350)*2;d.rectangle((px,py,px+1,py+1),fill=color(r['material']))
img.save(root/'overview.png')
# Pick a sloping part away from doorways for a closer perspective view.
points=[]
for r in curves:
 x,z=round(float(r['x'])),round(float(r['z']));cell=roadmap.get((x,z))
 if cell:points.append((x,z,int(cell['y'])))
best=None
for i in range(20,len(points)-20,8):
 patch=points[max(0,i-20):i+20];rise=max(p[2] for p in patch)-min(p[2] for p in patch)
 if best is None or rise>best[0]:best=(rise,*points[i])
cx,cz=(best[1],best[2]) if best else (0,0)
for view,(cx,cz,radius) in enumerate([(cx,cz,38),(0,0,65)]):
 selected={(x,z):r for (x,z),r in ground.items() if abs(x-cx)<=radius and abs(z-cz)<=radius}
 if not selected:continue
 base=min(int(r['y']) for r in selected.values())
 chosen_voxels=[r for r in voxels if (int(r['x']),int(r['z'])) in selected]
 W,H=1500,1000;scale=min(8,600/radius);vertical=scale*1.55
 heights=[(int(r['x'])-cx+int(r['z'])-cz)*scale*.48-(int(r['y'])+1-base)*vertical for r in list(selected.values())+chosen_voxels]
 low,high=min(heights),max(heights)
 if high-low>H-160:
  f=(H-160)/(high-low);scale*=f;vertical*=f;low*=f;high*=f
 offsetY=110+(H-120-(high-low))/2-low
 im=Image.new('RGB',(W,H),(207,225,237));draw=ImageDraw.Draw(im)
 def project(x,y,z):return (W/2+(x-cx-z+cz)*scale,offsetY+(x-cx+z-cz)*scale*.48-(y-base)*vertical)
 def poly(vertices,fill):draw.polygon([project(*p) for p in vertices],fill=fill)
 def box(x,y,z,w,h,depth,c):
  dark=tuple(int(v*.62) for v in c);mid=tuple(int(v*.80) for v in c)
  poly([(x+w,y,z),(x+w,y,z+depth),(x+w,y+h,z+depth),(x+w,y+h,z)],dark)
  poly([(x,y,z+depth),(x+w,y,z+depth),(x+w,y+h,z+depth),(x,y+h,z+depth)],mid)
  poly([(x,y+h,z),(x+w,y+h,z),(x+w,y+h,z+depth),(x,y+h,z+depth)],c)
 columns={key:[row] for key,row in selected.items()}
 for r in chosen_voxels:columns[(int(r['x']),int(r['z']))].append(r)
 for (x,z),records in sorted(columns.items(),key=lambda v:sum(v[0])):
  row=selected[(x,z)];ground_y=int(row['y'])
  for dx,dz in [(1,0),(0,1)]:
   neighbor=selected.get((x+dx,z+dz))
   if neighbor and int(neighbor['y'])<ground_y-1:
    lower=int(neighbor['y'])+1;soil=(112,91,65)
    if dx:poly([(x+1,lower,z),(x+1,lower,z+1),(x+1,ground_y,z+1),(x+1,ground_y,z)],soil)
    else:poly([(x,lower,z+1),(x+1,lower,z+1),(x+1,ground_y,z+1),(x,ground_y,z+1)],tuple(int(v*.85) for v in soil))
  for row in sorted(records,key=lambda r:int(r['y'])):
   material=row['material'];y=int(row['y']);c=color(material);data=row.get('data','')
   if material=='GLASS':
    # Outline actual glass blocks to keep the guard rail transparent in this diagnostic view.
    edges=[((x,y,z),(x+1,y,z)),((x,y+1,z),(x+1,y+1,z)),((x,y,z),(x,y+1,z)),((x+1,y,z),(x+1,y+1,z)),((x,y+1,z),(x,y+1,z+1)),((x+1,y+1,z),(x+1,y+1,z+1)),((x,y+1,z+1),(x+1,y+1,z+1))]
    for u,v in edges:draw.line([project(*u),project(*v)],fill=(174,216,225),width=1)
   elif material.endswith('_SLAB'):box(x,y,z,1,.5,1,c)
   elif material.endswith('_STAIRS'):
    facing=next((v for v in ['east','west','north','south'] if 'facing='+v in data),'east');box(x,y,z,1,.5,1,c)
    box(x+(.5 if facing=='east' else 0),y+.5,z+(.5 if facing=='south' else 0),.5 if facing in ('east','west') else 1,.5,.5 if facing in ('north','south') else 1,c)
   elif material=='WATER':
    if not any(int(r['y'])==y+1 and r['material']=='WATER' for r in records):box(x,y,z,1,.9,1,c)
   else:box(x,y,z,1,1,1,c)

 draw.rectangle((0,0,W,92),fill=(23,31,38));draw.text((25,14),'真实 Paper 方块数据 · 坡道近景' if view==0 else '真实 Paper 方块数据 · 中央道路连接',font=font,fill='white')
 draw.text((25,51),f'中心 X={cx}, Z={cz} · 软件方块重建视图，非客户端截图 · 保留实际楼梯/台阶高度与朝向',font=small,fill=(183,200,211))
 im.save(root/('slope.png' if view==0 else 'junction.png'))
