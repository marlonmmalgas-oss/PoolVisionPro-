package com.poolvisionpro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.*

data class Ball(val x:Float,val y:Float,val radius:Float,val color:Int)
data class Line(val x1:Float,val y1:Float,val x2:Float,val y2:Float,val kind:Int)
data class FrameAnalysis(val table:RectF?,val balls:List<Ball>,val lines:List<Line>,val status:String)

object VisionEngine {
    fun analyse(bitmap:Bitmap):FrameAnalysis {
        val step=max(2,max(bitmap.width,bitmap.height)/650); val w=max(1,bitmap.width/step); val h=max(1,bitmap.height/step)
        val px=IntArray(w*h); val felt=BooleanArray(w*h); val hsv=FloatArray(3)
        for(y in 0 until h) for(x in 0 until w) { val c=bitmap.getPixel(min(bitmap.width-1,x*step),min(bitmap.height-1,y*step)); px[y*w+x]=c; Color.colorToHSV(c,hsv); felt[y*w+x]=hsv[0] in 165f..215f && hsv[1]>.18f && hsv[2] in .25f.. .92f }
        var l=w;var t=h;var r=0;var b=0;var n=0
        for(y in 0 until h) for(x in 0 until w) if(felt[y*w+x]) { l=min(l,x);t=min(t,y);r=max(r,x);b=max(b,y);n++ }
        val table=if(n>w*h/35 && r-l>w/4 && b-t>h/8) RectF(l*step.toFloat(),t*step.toFloat(),r*step.toFloat(),b*step.toFloat()) else null
        val mask=BooleanArray(w*h)
        for(i in px.indices) { Color.colorToHSV(px[i],hsv); val white=hsv[2]>.72f&&hsv[1]<.34f; val colour=hsv[1]>.48f&&hsv[2]>.34f&&hsv[2]<.96f; mask[i]=!felt[i]&&(white||colour) }
        val seen=BooleanArray(w*h);val q=IntArray(w*h);val found=ArrayList<Ball>()
        for(start in mask.indices) { if(!mask[start]||seen[start]) continue
            var head=0;var tail=0;q[tail++]=start;seen[start]=true;var sx=0.0;var sy=0.0;var cr=0L;var cg=0L;var cb=0L;var x0=w;var x1=0;var y0=h;var y1=0
            while(head<tail) { val i=q[head++];val x=i%w;val y=i/w;val c=px[i];sx+=x;sy+=y;cr+=Color.red(c);cg+=Color.green(c);cb+=Color.blue(c);x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)
                for(dy in -1..1) for(dx in -1..1) { val xx=x+dx;val yy=y+dy;if(xx in 0 until w&&yy in 0 until h){val j=yy*w+xx;if(mask[j]&&!seen[j]){seen[j]=true;q[tail++]=j}} }
            }
            val bw=x1-x0+1;val bh=y1-y0+1;val ratio=bw.toFloat()/bh
            if(tail in 5..400 && ratio in .62f..1.62f && bw in 3..40 && bh in 3..40) found.add(Ball((sx/tail*step).toFloat(),(sy/tail*step).toFloat(),(bw+bh)*step/4f,Color.rgb((cr/tail).toInt(),(cg/tail).toInt(),(cb/tail).toInt())))
        }
        val balls=if(table!=null) found.filter{table.contains(it.x,it.y)} else found
        val cue=balls.minByOrNull{abs(255-Color.red(it.color))+abs(255-Color.green(it.color))+abs(255-Color.blue(it.color))}
        val lines=ArrayList<Line>()
        if(cue!=null&&table!=null) { val target=balls.filter{it!==cue}.minByOrNull{hypot((it.x-cue.x).toDouble(),(it.y-cue.y).toDouble())}; if(target!=null) {
            val dx=target.x-cue.x;val dy=target.y-cue.y;val len=max(1f,hypot(dx,dy));val ux=dx/len;val uy=dy/len
            lines.add(Line(cue.x,cue.y,target.x-ux*(cue.radius+target.radius),target.y-uy*(cue.radius+target.radius),0))
            val end=ray(target.x,target.y,ux,uy,table);lines.add(Line(target.x,target.y,end.first,end.second,1))
            val mirrors=listOf(Pair(2*table.left-target.x,target.y),Pair(2*table.right-target.x,target.y),Pair(target.x,2*table.top-target.y),Pair(target.x,2*table.bottom-target.y))
            mirrors.forEach{m->val vx=m.first-cue.x;val vy=m.second-cue.y;val vl=max(1f,hypot(vx,vy));val hit=ray(cue.x,cue.y,vx/vl,vy/vl,table);lines.add(Line(cue.x,cue.y,hit.first,hit.second,2))}
        }}
        return FrameAnalysis(table,balls,lines,if(balls.isEmpty()) "SEARCHING FOR BALLS" else "${balls.size} BALL CANDIDATES")
    }
    private fun ray(x:Float,y:Float,dx:Float,dy:Float,r:RectF):Pair<Float,Float>{val ts=ArrayList<Float>();if(dx>0)ts.add((r.right-x)/dx) else if(dx<0)ts.add((r.left-x)/dx);if(dy>0)ts.add((r.bottom-y)/dy) else if(dy<0)ts.add((r.top-y)/dy);val k=ts.filter{it>0}.minOrNull()?:0f;return Pair(x+dx*k,y+dy*k)}
}
