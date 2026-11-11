import math
from PIL import Image, ImageDraw
K=2; N=1024*K
def S(v): return v*K
img=Image.new("RGBA",(N,N),(0,0,0,0)); d=ImageDraw.Draw(img)
# background: rounded square with vertical gradient
bg=Image.new("RGBA",(N,N)); bd=ImageDraw.Draw(bg)
top,bot=(52,168,83),(24,98,52)
for y in range(N):
    t=y/N; bd.line([(0,y),(N,y)],fill=tuple(int(top[i]+(bot[i]-top[i])*t) for i in range(3))+(255,))
mask=Image.new("L",(N,N),0); ImageDraw.Draw(mask).rounded_rectangle([0,0,N-1,N-1],radius=S(230),fill=255)
img.paste(bg,(0,0),mask); d=ImageDraw.Draw(img)
def cube(cx,cy,s,cols):
    h=0.866*s; P=lambda x,y:(S(x),S(y))
    d.polygon([P(cx,cy-s),P(cx+h,cy-s/2),P(cx,cy),P(cx-h,cy-s/2)],fill=cols[0])
    d.polygon([P(cx-h,cy-s/2),P(cx,cy),P(cx,cy+s),P(cx-h,cy+s/2)],fill=cols[1])
    d.polygon([P(cx,cy),P(cx+h,cy-s/2),P(cx+h,cy+s/2),P(cx,cy+s)],fill=cols[2])
cols=((255,255,255,255),(214,243,222,255),(149,213,178,255))
cube(330,650,235,cols)
cube(715,440,150,cols)
# hop arc (quadratic bezier) with arrowhead
P0,P1,P2=(290,395),(320,110),(610,235)
pts=[((1-t)**2*P0[0]+2*(1-t)*t*P1[0]+t*t*P2[0],(1-t)**2*P0[1]+2*(1-t)*t*P1[1]+t*t*P2[1]) for t in [i/200 for i in range(201)]]
amber=(255,212,59,255); w=34
for x,y in pts: d.ellipse([S(x-w/2),S(y-w/2),S(x+w/2),S(y+w/2)],fill=amber)
ang=math.atan2(P2[1]-P1[1],P2[0]-P1[0]); L=95
tip=(P2[0]+math.cos(ang)*L*0.7,P2[1]+math.sin(ang)*L*0.7)
nx,ny=-math.sin(ang),math.cos(ang); b=(P2[0]-math.cos(ang)*L*0.1,P2[1]-math.sin(ang)*L*0.1)
d.polygon([(S(tip[0]),S(tip[1])),(S(b[0]+nx*62),S(b[1]+ny*62)),(S(b[0]-nx*62),S(b[1]-ny*62))],fill=amber)
out=img.resize((1024,1024),Image.LANCZOS)
out.resize((512,512),Image.LANCZOS).save("hosthop-logo-512.png")
out.resize((120,120),Image.LANCZOS).save("hosthop-logo-120.png")
print("ok")
