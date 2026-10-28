"""Deterministic synthetic manuals; no copyrighted rules. Requires reportlab and Pillow."""
from pathlib import Path
from io import BytesIO
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]/'app/src/test/resources/manuals'
ROOT.mkdir(parents=True,exist_ok=True)
RULE='Move exactly 3 spaces unless blocked. If tied, defender wins. See page 2.'
def new(name):return canvas.Canvas(str(ROOT/name),pagesize=(600,800),invariant=1)
def text(c,x=40,y=720):
 c.setFont('Helvetica-Bold',18);c.drawString(x,y,'Movement')
 c.setFont('Helvetica',10);c.drawString(x,y-30,RULE)
 c.drawString(x,y-50,'1. Spend 2 energy. 2. Do not move diagonally.')
 c.drawString(x,y-70,'Win with 12 points; lose when energy reaches 0.')
def image():
 im=Image.new('RGB',(1400,350),'white');d=ImageDraw.Draw(im)
 font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',28)
 for i,line in enumerate(['Move exactly 3 spaces unless blocked.','If tied, defender wins. See page 2.','Spend 2 energy. Never move diagonally.']):d.text((20,25+90*i),line,font=font,fill='black')
 return ImageReader(im)
c=new('native.pdf');text(c);c.showPage();text(c);c.save()
c=new('scanned.pdf');c.drawImage(image(),30,440,540,135);c.save()
c=new('hybrid.pdf');text(c);c.drawImage(image(),30,320,540,135);c.save()
c=new('columns.pdf');c.setFont('Helvetica',11)
for i,line in enumerate(['LEFT 1: spend 2 energy.','LEFT 2: move 3 spaces.','LEFT 3: unless blocked.']):c.drawString(35,720-25*i,line)
for i,line in enumerate(['RIGHT 1: defend first.','RIGHT 2: ties favor defender.','RIGHT 3: score 12 to win.']):c.drawString(325,720-25*i,line)
c.save()
c=new('tables.pdf');c.setFont('Helvetica',12)
for i,row in enumerate([('Action','Cost','Range'),('Move','2','3'),('Attack','4','1')]):
 for j,cell in enumerate(row):c.drawString(50+j*150,720-i*35,cell)
for i in range(4):c.line(40,735-i*35,520,735-i*35)
c.save()
c=new('diagrams.pdf');text(c);c.setStrokeColorRGB(0,0,0)
for x in range(4):c.rect(60+x*90,430,80,80)
c.line(100,470,370,470);c.line(370,470,355,485);c.drawString(70,400,'Arrow: move 3 spaces, unless blocked.');c.save()
c=new('symbols.pdf');text(c);c.circle(90,450,18);c.rect(130,432,36,36);c.drawString(50,400,'Circle = energy. Square = obstacle.');c.save()
c=new('blank.pdf');c.showPage();c.save()
c=new('large.pdf')
for page in range(1,121):text(c);c.drawString(40,40,str(page));c.showPage()
c.save()
(ROOT/'corrupt.pdf').write_bytes(b'%PDF-1.7\nnot a valid document')
